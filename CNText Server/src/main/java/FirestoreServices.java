import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import io.grpc.Status;
import io.grpc.StatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class FirestoreServices
{
    public static class AccountNotFoundException extends StatusException {
        public AccountNotFoundException(String username) {
            super(Status.NOT_FOUND.withDescription("Account whose username is " + username + " doesn't seem to exist."));
        }
    }

    public static class UsernameException extends StatusException {
        public UsernameException(String status) {
            super(Status.INVALID_ARGUMENT.withDescription("The username sent is " + status + "."));
        }
    }

    public static class SessionNotFoundException extends StatusException {
        public SessionNotFoundException(String sessionId) {
            super(Status.NOT_FOUND.withDescription("There is no session whose id is " + sessionId));
        }
    }

    public static class SessionException extends StatusException {
        public SessionException(String status) {
            super(Status.INVALID_ARGUMENT.withDescription("The session id sent is " + status + "."));
        }
    }

    public static class SubmissionNotFoundException extends StatusException {
        public SubmissionNotFoundException(String submissionId) {
            super(Status.NOT_FOUND.withDescription("There is no submission whose id is " + submissionId));
        }
    }

    public static class SubmissionException extends StatusException {
        public SubmissionException(String status) {
            super(Status.INVALID_ARGUMENT.withDescription("The submission id sent is " + status + "."));
        }
    }

    public static class SubmissionInfo
    {
        private String sessionId;
        private String state;
        private String error;
        private String text;
        private String textTranslated;
        private String translatedFrom;
        private String translatedTo;

        public SubmissionInfo() { }

        public SubmissionInfo(
            String sessionId,
            String state,
            String error,
            String text,
            String textTranslated,
            String translatedFrom,
            String translatedTo
        ) {
            this.sessionId = sessionId;
            this.state = state;
            this.error = error;
            this.text = text;
            this.textTranslated = textTranslated;
            this.translatedFrom = translatedFrom;
            this.translatedTo = translatedTo;
        }

        public String getSessionId() { return sessionId; }

        public String getTranslatedTo() {
            return translatedTo;
        }

        public String getTranslatedFrom() {
            return translatedFrom;
        }

        public String getTextTranslated() {
            return textTranslated;
        }

        public String getText() {
            return text;
        }

        public String getState() {
            return state;
        }

        public String getError() { return error; }
    }

    private final Firestore database;

    public FirestoreServices(String projectId, GoogleCredentials credentials)
    {
        FirestoreOptions options = FirestoreOptions
            .newBuilder()
            .setCredentials(credentials)
            .setProjectId(projectId)
            .build();

        database = options.getService();
    }

    public String storeSession(String username) throws StatusException, ExecutionException, InterruptedException
    {
        if(username == null)
            throw new UsernameException("empty");

        if(username.isEmpty())
            throw new UsernameException("missing");

        final DocumentReference userDocRef = database.collection("users").document(username);
        final DocumentReference sessionDocRef = database.collection("sessions").document();

        Object result = database.runTransaction(transaction ->
            {
                if(!transaction.get(userDocRef).get().exists())
                    return new AccountNotFoundException(username);

                Map<String, String> create = new HashMap<>();
                create.put("username", username);

                transaction.set(sessionDocRef, create);

                return sessionDocRef.getId();
            }
        ).get();

        if(result instanceof StatusException)
            throw (StatusException) result;

        return (String) result;
    }

    public String storeSubmission(String sessionId) throws StatusException, ExecutionException, InterruptedException
    {
        if(sessionId == null)
            throw new SessionException("empty");

        if(sessionId.isEmpty())
            throw new SessionException("missing");

        final DocumentReference sessionDocRef = database.collection("sessions").document(sessionId);
        final DocumentReference submissionDocRef = database.collection("submissions").document();

        Object result = database.runTransaction(transaction ->
            {
                if(!transaction.get(sessionDocRef).get().exists())
                    return new SessionNotFoundException(sessionId);

                Map<String, String> create = new HashMap<>();
                create.put("sessionId", sessionId);
                create.put("state", "in progress");

                transaction.set(submissionDocRef, create);

                return submissionDocRef.getId();
            }
        ).get();

        if(result instanceof StatusException)
            throw (StatusException) result;

        return (String) result;
    }

    public SubmissionInfo getSubmissionInfo(String sessionId, String submissionId) throws StatusException, ExecutionException, InterruptedException
    {
        if(sessionId == null)
            throw new SessionException("empty");

        if(sessionId.isEmpty())
            throw new SessionException("missing");

        if(submissionId == null)
            throw new SubmissionException("empty");

        if(submissionId.isEmpty())
            throw new SubmissionException("missing");

        final DocumentReference sessionDocRef = database.collection("sessions").document(sessionId);
        final DocumentReference submissionDocRef = database.collection("submissions").document(submissionId);

        Object result = database.runTransaction(transaction ->
            {
                if(!transaction.get(sessionDocRef).get().exists())
                    return new SessionNotFoundException(sessionId);

                DocumentSnapshot submissionDocSnap = transaction.get(submissionDocRef).get();

                if(!submissionDocSnap.exists())
                    return new SubmissionNotFoundException(submissionId);

                return submissionDocSnap.toObject(SubmissionInfo.class);
            }
        ).get();

        if(result instanceof StatusException)
            throw (StatusException) result;

        return (SubmissionInfo) result;
    }

    public void closeSession(String sessionId) throws StatusException, ExecutionException, InterruptedException
    {
        if(sessionId == null)
            throw new SessionException("empty");

        if(sessionId.isEmpty())
            throw new SessionException("missing");

        final DocumentReference docRef = database.collection("sessions").document(sessionId);

        SessionNotFoundException result = database.runTransaction(transaction ->
            {
                if(!transaction.get(docRef).get().exists())
                    return new SessionNotFoundException(sessionId);

                transaction.delete(docRef);

                return null;
            }
        ).get();

        if(result != null)
            throw result;
    }

    public void closeSessions() throws ExecutionException, InterruptedException
    {
        final CollectionReference collectionRef = database.collection("sessions");

        int deleted = 0;

        do {
            deleted = database.runTransaction(transaction ->
                {
                    List<QueryDocumentSnapshot> documents = transaction.get(collectionRef.limit(25)).get().getDocuments();

                    int temp = 0;

                    for (QueryDocumentSnapshot document : documents) {
                        document.getReference().delete();
                        ++temp;
                    }

                    return temp;
                }
            ).get();
        } while(deleted >= 25);
    }

    public void deleteSubmissions() throws ExecutionException, InterruptedException
    {
        final CollectionReference collectionRef = database.collection("submissions");

        int deleted = 0;

        do {
            deleted = database.runTransaction(transaction ->
                {
                    List<QueryDocumentSnapshot> documents = transaction.get(collectionRef.limit(25)).get().getDocuments();

                    int temp = 0;

                    for (QueryDocumentSnapshot document : documents) {
                        document.getReference().delete();
                        ++temp;
                    }

                    return temp;
                }
            ).get();
        } while(deleted >= 25);
    }

    public String validateSession(String sessionId) throws StatusException, ExecutionException, InterruptedException
    {
        if(sessionId == null)
            throw new SessionException("empty");

        if(sessionId.isEmpty())
            throw new SessionException("missing");

        final DocumentReference sessionDocRef = database.collection("sessions").document(sessionId);
        final CollectionReference usersCollectionRef = database.collection("users");

        Object result = database.runTransaction(transaction ->
            {
                DocumentSnapshot sessionDocSnap = transaction.get(sessionDocRef).get();

                if(!sessionDocSnap.exists())
                    return new SessionNotFoundException(sessionId);

                String username = sessionDocSnap.get("username", String.class);

                final DocumentReference userDocRef = usersCollectionRef.document(username);

                return transaction.get(userDocRef).get().get("serviceLevel", String.class);
            }
        ).get();

        if(result instanceof StatusException)
            throw (StatusException) result;

        return (String) result;
    }
}