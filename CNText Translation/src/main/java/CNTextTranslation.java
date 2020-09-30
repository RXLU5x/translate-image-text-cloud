import com.google.api.gax.core.ExecutorProvider;
import com.google.api.gax.core.InstantiatingExecutorProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.common.base.Charsets;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CNTextTranslation
{
    public final static ExecutorService executor = Executors.newFixedThreadPool(5);

    private final static Logger logger = LogManager.getLogger(CNTextTranslation.class);

    private final static String DEFAULT_PROJECT_ID = "g02-leirt61d-v1920";
    private final static String DEFAULT_SERVICE_LEVEL = "free";

    private static void processMessage (
        FirestoreServices firestoreServices,
        TranslateServices translateServices,
        PubsubMessage bytes,
        AckReplyConsumer acknowledge
    ) {
        Map<String, String> attributes = bytes.getAttributesMap();
        String textToTranslate = bytes.getData().toString(Charsets.UTF_8);

        String submissionId = attributes.get("submissionId");

        try {
            String from = translateServices.detectLanguage(textToTranslate);
            String to = attributes.get("to");
            String translatedText = translateServices.translateText(textToTranslate, from, to);
            firestoreServices.storeTextTranslationResult(submissionId, translatedText, from, to);
            acknowledge.ack();
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            setSubmissionErrorState(firestoreServices, submissionId, "CNTextTranslation module: " + ex.getMessage());
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

            FirestoreServices firestoreServices = new FirestoreServices(projectId, credentials, FirestoreServices.DEFAULT_IMAGES_TEXT_TRANSLATION_RESULTS_COLLECTION_NAME);
            TranslateServices translateServices = new TranslateServices();

            ProjectSubscriptionName projectSubscriptionName;
            MessageReceiver receiver = (bytes, acknowledge) -> processMessage(firestoreServices, translateServices, bytes, acknowledge);
            ExecutorProvider executorProvider = null;

            if(serviceLevel.compareToIgnoreCase("premium") == 0)
            {
                projectSubscriptionName = ProjectSubscriptionName
                    .newBuilder()
                    .setProject(projectId)
                    .setSubscription(SubscriberServices.PREMIUM_TRANSLATION_WORKERS_SUBSCRIPTION_ID)
                    .build();
            }

            else
            {
                executorProvider = InstantiatingExecutorProvider
                    .newBuilder()
                    .setExecutorThreadCount(1)
                    .build();

                projectSubscriptionName = ProjectSubscriptionName
                    .newBuilder()
                    .setProject(projectId)
                    .setSubscription(SubscriberServices.FREE_TRANSLATION_WORKER_SUBSCRIPTION_ID)
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