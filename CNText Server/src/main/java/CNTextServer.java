import com.google.api.services.compute.ComputeScopes;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import ipl.isel.cn.group2.contract.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CNTextServer extends CNTextServiceGrpc.CNTextServiceImplBase
{
    private static final Logger logger = LogManager.getRootLogger();

    private static final String DEFAULT_PROJECT_ID = "g02-leirt61d-v1920";
    private static final int DEFAULT_PORT = 8000;

    private final FirestoreServices firestoreServices;
    private final StorageServices storageServices;
    private final ComputeServices computeServices;

    private final Server server;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public CNTextServer(
        int port,
        FirestoreServices firestoreServices,
        StorageServices storageServices,
        ComputeServices computeServices
    ) {
        this.firestoreServices = firestoreServices;
        this.storageServices = storageServices;
        this.computeServices = computeServices;

        this.server = ServerBuilder
            .forPort(port)
            .addService(this)
            .build();
    }

    @Override
    public void signIn(Username username, StreamObserver<Session> responseObserver)
    {
        try {
            String sessionId = firestoreServices.storeSession(username.getValue());
            Session session = Session.newBuilder().setId(sessionId).build();

            logger.info("Session whose id is " + sessionId + ", is now active.");

            responseObserver.onNext(session);
            responseObserver.onCompleted();
        } catch (StatusException e) {
            logger.error(e.getMessage());
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error(e.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void signOut(Session session, StreamObserver<Empty> responseObserver)
    {
        try {
            firestoreServices.closeSession(session.getId());

            logger.info("Session whose id is " + session.getId() + ", is now inactive.");

            Empty empty = Empty.newBuilder().build();
            responseObserver.onNext(empty);

            responseObserver.onCompleted();
        } catch (StatusException e) {
            logger.error(e.getMessage());
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error(e.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public StreamObserver<Image> submitImageTextTranslationRequest(StreamObserver<Submission> responseObserver) {
        return new ImageObserver(responseObserver, firestoreServices, storageServices, StorageServices.DEFAULT_BUCKET_ID);
    }

    @Override
    public void requestImageTextTranslationResult(Request request, StreamObserver<ImageTextTranslated> responseObserver) {
        try {
            String sessionId = request.getSession().getId();
            String submissionId = request.getSubmission().getId();

            FirestoreServices.SubmissionInfo submissionInfo = firestoreServices.getSubmissionInfo(sessionId, submissionId);

            String submissionState = submissionInfo.getState();

            if(submissionState.compareToIgnoreCase("error") == 0) {
                String error = submissionInfo.getError();
                throw Status.UNAVAILABLE.withDescription("Submission encountered an error. " + error).asException();
            }

            else if(!(submissionState.compareToIgnoreCase("completed") == 0))
                throw Status.UNAVAILABLE.withDescription("Submission isn't ready yet. Current state is " + submissionState).asException();

            ImageTextTranslated imageTextTranslated = ImageTextTranslated
                .newBuilder()
                .setTranslatedText(submissionInfo.getTextTranslated())
                .setTranslatedFrom(submissionInfo.getTranslatedFrom())
                .setTranslatedTo(submissionInfo.getTranslatedTo())
                .build();

            responseObserver.onNext(imageTextTranslated);
            responseObserver.onCompleted();
        } catch (StatusException ex) {
            logger.error(ex.getMessage());
            responseObserver.onError(ex);
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(ex.getMessage()).asException());
        }
    }

    private void start() throws IOException, ExecutionException, InterruptedException {
        logger.info("Cleaning older sessions and submissions.");
        firestoreServices.deleteSubmissions();
        firestoreServices.closeSessions();

        Runnable runnable = () -> {
            try {
                int newSize = ImageObserver.numberOfPremiumSessions.intValue() + 2;
                logger.info("Adjusting instance groups size to " + newSize);
                computeServices.resizeInstanceGroup(ComputeServices.DEFAULT_PREMIUM_OCR_INSTANCE_GROUP_ID, newSize);
                computeServices.resizeInstanceGroup(ComputeServices.DEFAULT_PREMIUM_TRANSLATION_INSTANCE_GROUP_ID, newSize);
            } catch (InterruptedException | IOException e) {
                logger.error("Couldn't resize the number of instances.");
            }
        };

        executor.scheduleAtFixedRate(runnable, 2, 2, TimeUnit.MINUTES);

        server.start();

        System.out.println("Server started, listening on " + server.getPort());

        Scanner scan = new Scanner(System.in); scan.nextLine();
    }

    private void shutdown(int status) {
        server.shutdown();
        executor.shutdown();
        System.exit(status);
    }

    public static void main(String... args)
    {
        String projectId = DEFAULT_PROJECT_ID;
        int port = DEFAULT_PORT;

        Pattern portPattern = Pattern.compile("^-p=([\\d]+)$");
        Pattern projectIdPattern = Pattern.compile("^-i=(\\b[\\w-]+\\b)$");

        for (String arg : args) {
            Matcher portMatcher = portPattern.matcher(arg);
            Matcher projectIdMatcher = projectIdPattern.matcher(arg);

            if(portMatcher.matches())
                port = Integer.parseInt(portMatcher.group(1));
            else if(projectIdMatcher.matches())
                projectId = projectIdMatcher.group(1);
        }

        try {
            List<String> scopes = new ArrayList<>();
            scopes.add(ComputeScopes.COMPUTE);

            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            GoogleCredentials computeEngineCredentials = credentials.createScoped(scopes);

            FirestoreServices firestoreServices = new FirestoreServices(projectId, credentials);
            StorageServices storageServices = new StorageServices(projectId, credentials);
            ComputeServices computeServices = new ComputeServices(projectId, computeEngineCredentials, "us-central1-a");

            CNTextServer server = new CNTextServer(port, firestoreServices, storageServices, computeServices);
            server.start();

            server.shutdown(0);
        } catch (Exception e) {
            logger.error(e.getMessage());
        }
    }
}
