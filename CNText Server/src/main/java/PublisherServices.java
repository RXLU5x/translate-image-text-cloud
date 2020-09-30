import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class PublisherServices
{
    final private Publisher publisher;

    public PublisherServices(TopicName topicName) throws IOException {
        publisher = Publisher
            .newBuilder(topicName)
            .build();
    }

    public void publish(String message, Map<String, String> attributes) throws InterruptedException, ExecutionException {
        try {
            ByteString data = ByteString.copyFromUtf8(message);

            PubsubMessage pubsubMessage = PubsubMessage
                .newBuilder()
                .setData(data)
                .putAllAttributes(attributes)
                .build();

            publisher.publish(pubsubMessage).get();
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}