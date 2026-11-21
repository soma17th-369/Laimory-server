package com.laimory.server.aidispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** SQS 어댑터(#515)의 요청 조립과 적재 실패 분류. 네트워크는 쓰지 않는다(client mock). */
class SqsDispatchQueueTest {

    private static final String QUEUE_URL =
            "https://sqs.ap-northeast-2.amazonaws.com/000000000000/laimory-dev-ai-dispatch-timeline";

    private final SqsClient client = mock(SqsClient.class);
    private final SqsDispatchQueue queue = new SqsDispatchQueue(client, QUEUE_URL);

    @Test
    void sendPutsBodyOnConfiguredQueue() {
        queue.send("{\"taskId\":\"t1\"}");

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.getValue().messageBody()).isEqualTo("{\"taskId\":\"t1\"}");
    }

    @Test
    void clientErrorOnSendIsConfirmedNotEnqueued() {
        when(client.sendMessage(any(SendMessageRequest.class))).thenThrow(sqsError(403, "AccessDenied"));

        assertThatThrownBy(() -> queue.send("{}"))
                .isInstanceOf(DispatchQueueSendRejectedException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("AccessDenied");
    }

    @Test
    void serverErrorOnSendIsUnknownAndPropagatesAsIs() {
        SqsException serverError = sqsError(500, "InternalError");
        when(client.sendMessage(any(SendMessageRequest.class))).thenThrow(serverError);

        assertThatThrownBy(() -> queue.send("{}")).isSameAs(serverError);
    }

    @Test
    void receiveLongPollsForFreeSlotsAndMapsAttributes() {
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body("{}")
                        .receiptHandle("r1")
                        .attributes(Map.of(
                                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "3",
                                MessageSystemAttributeName.SENT_TIMESTAMP, "1763694000000"))
                        .build())
                .build());

        List<ReceivedDispatch> received = queue.receive(4);

        ArgumentCaptor<ReceiveMessageRequest> request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(client).receiveMessage(request.capture());
        assertThat(request.getValue().maxNumberOfMessages()).isEqualTo(4);
        assertThat(request.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(received).containsExactly(
                new ReceivedDispatch("{}", "r1", 3, Instant.ofEpochMilli(1763694000000L)));
    }

    @Test
    void retryAfterChangesVisibilityToDelay() {
        queue.retryAfter("r1", Duration.ofSeconds(5));

        ArgumentCaptor<ChangeMessageVisibilityRequest> request =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(client).changeMessageVisibility(request.capture());
        assertThat(request.getValue().receiptHandle()).isEqualTo("r1");
        assertThat(request.getValue().visibilityTimeout()).isEqualTo(5);
    }

    private static SqsException sqsError(int status, String code) {
        return (SqsException) SqsException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .message(code)
                .build();
    }
}
