package com.laimory.server.timeline.photo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 in-memory {@link PhotoStorageService} 구현.
 *
 * <p>presigned PUT URL 발급 시 objectKey→content-type을 맵에 기록하고(결정적 fake URL 반환),
 * delete로 제거한다. PR2/PR3 테스트가 실제 S3 없이 발급/삭제 호출을 검증할 때 재사용한다
 * (이 fake 자체에 대한 테스트는 없다).
 */
public class FakePhotoStorageService implements PhotoStorageService {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String generatePresignedPutUrl(String objectKey, String contentType) {
        store.put(objectKey, contentType == null ? "" : contentType);
        return "https://fake-presign/" + objectKey;
    }

    @Override
    public void delete(String objectKey) {
        store.remove(objectKey);
    }

    /** 해당 key에 대한 presigned URL이 발급되어 기록돼 있는지. */
    public boolean contains(String objectKey) {
        return store.containsKey(objectKey);
    }

    /** 발급 시 기록된 content-type(없으면 null). */
    public String contentTypeOf(String objectKey) {
        return store.get(objectKey);
    }

    /** 현재 기록된 객체 수. */
    public int size() {
        return store.size();
    }
}
