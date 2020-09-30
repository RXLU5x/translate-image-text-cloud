import com.google.api.gax.core.ExecutorProvider;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.storage.BlobId;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.common.base.Charsets;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CNTextOCR
{
    private final static Logger logger = LogManager.getLogger(CNTextOCR.class);

    private final static String DEFAULT_PROJECT_ID = "g02-leirt61d-v1920";
    private final static String DEFAULT_SERVICE_LEVEL = "free";

    private static void processMessage (
        StorageServices storageServices,
        VisionServices visionServices,
        FirestoreServices firestoreServices,
        TopicName topicName,
        PubsubMessage bytes,
        AckReplyConsumer acknowledge
    ) {
        Map<String, String> attributes = bytes.getAttributesMap();
        String submissionId = attributes.get("submissionId");

        String blobName = bytes.getData().toString(Charsets.UTF_8);

        BlobId blobId = StorageServices.getBlobId(StorageServices.DEFAULT_BUCKET_ID, blobName);
        Image image = storageServices.getImage(blobId);

        Feature feature = Feature
            .newBuilder()
            .setType(VisionServices.DEFAULT_FEATURE_TYPE)
            .build();

        String imageText = visionServices.detectImageText(image, feature);

        try {
            firestoreServices.storeTextDetectionResult(submissionId, imageText);
            storageServices.deleteImage(blobId);

            PublisherServices publisherServices = new PublisherServices(topicName);
            publisherServices.publish(imageText, attributes);

            acknowledge.ack();
        } catch (Exception e) {
            logger.error(e.getMessage());
            setSubmissionErrorState(firestoreServices, submissionId, "CNTextOCR module: " + e.getMessage());
            acknowledge.ack();
        }
    }

    private static void setSubmissionErrorState(FirestoreServices firestoreServices, String submissionId, String details) {
        try {
            firestoreServices.setSubmissionErrorState(submissionId, details);
        } catch (ExecutionException | InterruptedException | FirestoreServices.SubmissionNotFoundException ex) {
            logger.error(ex.getMessage());
        }
    }

    public static void main(String... args)
    {
        String serviceLevel = DEFAULT_SERVICE_LEVEL;
        String projectId = DEFAULT_PROJECT_ID;

        Pattern premiumLevelPattern = Pattern.compile("^-p$");
        Pattern projectIdPattern = Pattern.compile("^-i=(\\b[\\w-]+\\b)$");

        for (String arg : args)
        {
            Matcher premiumLevelMatcher = premiumLevelPattern.matcher(arg);
            Matcher projectIdMatcher = projectIdPattern.matcher(arg);

            if(premiumLevelMatcher.matches())
                serviceLevel = "premium";

            else if(projectIdMatcher.matches())
                projectId = projectIdMatcher.group(1);
        }

        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();

            StorageServices storageServices = new StorageServices(projectId, credentials);
            VisionServices visionServices = new VisionServices();
            FirestoreServices firestoreServices = new FirestoreServices(projectId, credentials, FirestoreServices.DEFAULT_IMAGES_TEXT_DETECTION_RESULTS_COLLECTION_NAME);

            ProjectSubscriptionName projectSubscriptionName;
            MessageReceiver receiver;

            ExecutorProvider executorProvider = null;

            if(serviceLevel.compareToIgnoreCase("premium") == 0)
            {
                TopicName topicName = TopicName
                    .newBuilder()
                    .setProject(projectId)
                    .setTopic(PublisherServices.PREMIUM_TRANSLATE_TOPIC_ID)
                    .build();

                receiver = (bytes, acknowledge) -> processMessage(storageServices, visionServices, firestoreServices, topicName, bytes, acknowledge);

                projectSubscriptionName = ProjectSubscriptionName
                    .newBuilder()
                    .setProject(projectId)
                    .setSubscription(SubscriberServices.PREMIUM_OCR_WORKERS_SUBSCRIPTION_ID)
                    .build();
            }

            else
            {
                executorProvider = InstantiatingExecutorProvider
                    .newBuilder()
                    .setExecutorThreadCount(1)
                    .build();

                TopicName topicName = TopicName
                    .newBuilder()
                    .setProject(projectId)
                    .setTopic(PublisherServices.FREE_TRANSLATE_TOPIC_ID)
                    .build();

                receiver = (bytes, acknowledge) -> processMessage(storageServices, visionServices, firestoreServices, topicName, bytes, acknowledge);

                projectSubscriptionName = ProjectSubscriptionName
                    .newBuilder()
                    .setProject(projectId)
                    .setSubscription(SubscriberServices.FREE_OCR_WORKER_SUBSCRIPTION_ID)
                    .build();
            }

            System.out.println("Subscribed to " + projectSubscriptionName.getSubscription() + " subscription");

            SubscriberServices subscriberServices = new SubscriberServices(projectSubscriptionName, executorProvider, receiver);
            subscriberServices.subscribe();
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }
}