import com.google.api.gax.core.ExecutorProvider;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;

public class SubscriberServices
{
    public final static String FREE_TRANSLATION_WORKER_SUBSCRIPTION_ID = "free-translation-worker-subscription";
    public final static String PREMIUM_TRANSLATION_WORKERS_SUBSCRIPTION_ID = "premium-translation-workers-subscription";

    private final Subscriber subscriber;

    public SubscriberServices(
        ProjectSubscriptionName projectSubscriptionName,
        ExecutorProvider executorProvider,
        MessageReceiver messageReceiver
    ) {
        Subscriber.Builder builder = Subscriber
                .newBuilder(projectSubscriptionName, messageReceiver);

        if(executorProvider != null)
            builder.setExecutorProvider(executorProvider);

        subscriber = builder.build();
    }

    public void subscribe() {
        // Start the subscriber.
        subscriber.startAsync();

        // Allow the subscriber to run indefinitely unless an unrecoverable error occurs
        subscriber.awaitTerminated();
    }
}
