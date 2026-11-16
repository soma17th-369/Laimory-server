"""handler.py 단위 테스트 — S3·R2를 메모리 fake로 바꿔 복제 결과(R2 상태)를 검증한다.

실행: python3 -m unittest discover -s deploy/aws/photo-r2-replicator
"""

import contextlib
import io
import unittest

import handler

SOURCE_BUCKET = "source-bucket"
R2_BUCKET = "r2-bucket"
KEY = "0" * 64 + "/photos/01a1269a-055e-730d-9e08-72c9021bb69d.jpg"


class NotFoundError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class Body:
    def __init__(self, data):
        self._data = data

    def read(self):
        return self._data


class FakeS3:
    def __init__(self, objects=None):
        self.objects = dict(objects or {})
        self.delete_after_get = False

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise NotFoundError("NoSuchKey")
        data, content_type = self.objects[Key]
        if self.delete_after_get:
            del self.objects[Key]
        return {"Body": Body(data), "ContentType": content_type}

    def head_object(self, Bucket, Key):
        if Key not in self.objects:
            raise NotFoundError("404")
        return {}


class FakeR2:
    def __init__(self, objects=None):
        self.objects = dict(objects or {})

    def put_object(self, Bucket, Key, Body, ContentType, Metadata):
        self.objects[Key] = {"body": Body, "content_type": ContentType, "metadata": Metadata}

    def delete_object(self, Bucket, Key):
        self.objects.pop(Key, None)


class FailingR2(FakeR2):
    def put_object(self, **kwargs):
        raise ConnectionError("r2 unavailable")


def s3_event(event_name, key=KEY):
    return {"Records": [{
        "eventName": event_name,
        "s3": {"bucket": {"name": SOURCE_BUCKET}, "object": {"key": key}},
    }]}


def run(event, s3, r2):
    handler._clients = (s3, r2, R2_BUCKET)
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        handler.handler(event, None)
    return output.getvalue()


class ReplicatorTest(unittest.TestCase):
    def tearDown(self):
        handler._clients = None

    def testCreatedObjectIsCopiedWithContentTypeAndMarker(self):
        s3 = FakeS3({KEY: (b"jpeg-bytes", "image/jpeg")})
        r2 = FakeR2()

        run(s3_event("ObjectCreated:Put"), s3, r2)

        self.assertEqual(r2.objects[KEY], {
            "body": b"jpeg-bytes", "content_type": "image/jpeg", "metadata": {"replicated-from": "s3"}})

    def testCreatedEventForObjectAlreadyDeletedInS3SkipsWithoutWriting(self):
        r2 = FakeR2()

        log = run(s3_event("ObjectCreated:Put"), FakeS3(), r2)

        self.assertEqual(r2.objects, {})
        self.assertIn('"skipped_missing"', log)

    def testObjectDeletedInS3DuringCopyIsRemovedFromR2(self):
        s3 = FakeS3({KEY: (b"jpeg-bytes", "image/jpeg")})
        s3.delete_after_get = True
        r2 = FakeR2()

        log = run(s3_event("ObjectCreated:Put"), s3, r2)

        self.assertEqual(r2.objects, {})
        self.assertIn('"rechecked_deleted"', log)

    def testRemovedEventDeletesR2Copy(self):
        r2 = FakeR2({KEY: {"body": b"x"}})

        run(s3_event("ObjectRemoved:Delete"), FakeS3(), r2)

        self.assertEqual(r2.objects, {})

    def testRemovedEventForKeyMissingInR2Succeeds(self):
        r2 = FakeR2()

        log = run(s3_event("ObjectRemoved:Delete"), FakeS3(), r2)

        self.assertEqual(r2.objects, {})
        self.assertIn('"deleted"', log)

    def testUrlEncodedKeyIsDecoded(self):
        key = "notices/a b.png"
        s3 = FakeS3({key: (b"png", "image/png")})
        r2 = FakeR2()

        run(s3_event("ObjectCreated:Put", key="notices/a+b.png"), s3, r2)

        self.assertIn(key, r2.objects)

    def testS3TestEventIsIgnored(self):
        r2 = FakeR2()

        log = run({"Event": "s3:TestEvent", "Service": "Amazon S3"}, FakeS3(), r2)

        self.assertEqual(r2.objects, {})
        self.assertIn('"ignored"', log)

    def testR2FailurePropagatesForRetry(self):
        s3 = FakeS3({KEY: (b"jpeg-bytes", "image/jpeg")})

        with self.assertRaises(ConnectionError):
            run(s3_event("ObjectCreated:Put"), s3, FailingR2())

    def testS3ErrorOtherThanNotFoundPropagates(self):
        class DeniedS3(FakeS3):
            def get_object(self, Bucket, Key):
                raise NotFoundError("AccessDenied")

        with self.assertRaises(NotFoundError):
            run(s3_event("ObjectCreated:Put"), DeniedS3(), FakeR2())

    def testLogsDoNotContainObjectKey(self):
        s3 = FakeS3({KEY: (b"jpeg-bytes", "image/jpeg")})

        log = run(s3_event("ObjectCreated:Put"), s3, FakeR2())
        log += run(s3_event("ObjectRemoved:Delete"), s3, FakeR2())

        self.assertNotIn("0" * 64, log)
        self.assertNotIn("photos", log)


if __name__ == "__main__":
    unittest.main()
