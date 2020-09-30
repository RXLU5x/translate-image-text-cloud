import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import io.grpc.Status;
import io.grpc.StatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class FirestoreServices
{
    public final static String DEFAULT_IMAGES_TEXT_DETECTION_RESULTS_COLLECTION_NAME = "submissions";

    private final Firestore firestore;
    private final String collectionName;

    public static class SubmissionNotFoundException extends StatusException {
        public SubmissionNotFoundException(String submissionId) {
            super(Status.NOT_FOUND.withDescription("There is no submission whose id is " + submissionId));
        }
    }

    public FirestoreServices(String projectId, GoogleCredentials credentials, String collectionName) {
        firestore = FirestoreOptions
            .newBuilder()
            .setProjectId(projectId)
            .setCredentials(credentials)
            .build()
            .getService();

        this.collectionName = collectionName;
    }

    public void setSubmissionErrorState(String submissionId, String details) throws ExecutionException, InterruptedException, SubmissionNotFoundException
    {
        final DocumentReference submissionDocRef = firestore.collection("submissions").document(submissionId);

        SubmissionNotFoundException result = firestore.runTransaction(transaction ->
            {
                if (!transaction.get(submissionDocRef).get().exists())
                    return new SubmissionNotFoundException(submissionId);

                Map<String, Object> update = new HashMap<>();
                update.put("state", "error");
                update.put("error", details);

                transaction.update(submissionDocRef, update);

                return null;
            }
        ).get();

        if(result != null)
            throw result;
    }

    public void storeTextDetectionResult(String submissionId, String result) throws ExecutionException, InterruptedException
    {
        DocumentReference docRef = firestore.collection(collectionName).document(submissionId);

        firestore.runTransaction(transaction ->
            {
                Map<String, Object> update = new HashMap<>();
                update.put("state", "detected");
                update.put("text", result);

                transaction.update(docRef, update);

                return null;
            }
        ).get();
    }
}