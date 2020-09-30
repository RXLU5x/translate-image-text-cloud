import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import ipl.isel.cn.group2.contract.*;
import ipl.isel.cn.group2.contract.CNTextServiceGrpc.CNTextServiceBlockingStub;
import ipl.isel.cn.group2.contract.CNTextServiceGrpc.CNTextServiceStub;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CNTextClient
{
    private static final Scanner scanner = new Scanner(System.in);
    private static final Logger logger = LogManager.getRootLogger();
    private static final Logger translations = LogManager.getLogger("TRANSLATIONS");

    private final CNTextServiceBlockingStub blockingStub;
    private final CNTextServiceStub stub;

    public static int DEFAULT_SERVER_PORT = 8000;
    public static String DEFAULT_SERVER_IP = "localhost";

    private static final int CHUNK_MAX_SIZE_BYTES = 1_000_000;

    public CNTextClient(
        CNTextServiceBlockingStub blockingStub,
        CNTextServiceStub stub
    ) {
        this.blockingStub = blockingStub;
        this.stub = stub;
    }

    public void signIn() throws StatusException
    {
        System.out.print("Please input your username: ");
        String usernameValue = scanner.nextLine();

        Username username = Username.newBuilder().setValue(usernameValue).build();

        Session session = blockingStub.signIn(username);

        System.out.println("\nHere's your new session id: " + session.getId() + ".");

        logger.info("User whose username is " + usernameValue + " signed in. Session established with id " + session.getId() + ".");
    }

    public void signOut() throws StatusException
    {
        System.out.print("Please input your session id: ");
        String sessionId = scanner.nextLine();

        Session session = Session.newBuilder().setId(sessionId).build();

        blockingStub.signOut(session);

        System.out.println("\nSession closed successfully.");

        logger.info("Session whose id is " + sessionId + " is now inactive.");
    }

    public void submitTranslationRequest() throws Exception
    {
        System.out.print("Please input your session id: ");
        String sessionId = scanner.nextLine();

        if(sessionId.isEmpty())
            throw Status.INVALID_ARGUMENT.withDescription("session id can't be empty").asException();

        SubmissionObserver response = new SubmissionObserver();
        StreamObserver<Image> request = stub.submitImageTextTranslationRequest(response);

        System.out.print("Please input the filename of the image that you want to translate: ");
        String imageName = scanner.nextLine();

        if(imageName.isEmpty())
            throw Status.INVALID_ARGUMENT.withDescription("image name can't be empty").asException();

        File imageFile = new File("images/" + imageName);

        long size = imageFile.length();

        System.out.print("Please input the ISO-639-1 code of the language that you want to translate the image text to: ");
        String translateTo = scanner.nextLine();

        Session session = Session
            .newBuilder()
            .setId(sessionId)
            .build();

        Image.Metadata metadata = Image.Metadata
            .newBuilder()
            .setSession(session)
            .setName(imageName)
            .setSize(size)
            .setTranslateTo(translateTo)
            .build();

        Image imageMetadata = Image
            .newBuilder()
            .setMetadata(metadata)
            .build();

        request.onNext(imageMetadata);

        try(FileInputStream imgReader = new FileInputStream(imageFile))
        {
            int numberOfBytesRead;

            byte[] chunk = new byte[CHUNK_MAX_SIZE_BYTES];

            while((numberOfBytesRead = imgReader.read(chunk)) != -1) {
                Image imageChunk = Image.newBuilder().setChunk(ByteString.copyFrom(ByteBuffer.wrap(chunk), numberOfBytesRead)).build();
                request.onNext(imageChunk);
            }
        } catch (Exception ex) {
            request.onError(Status.INTERNAL.withDescription(ex.getMessage()).asException());
            throw ex;
        }

        request.onCompleted();

        while(!response.isDone()) {}

        if(response.hasError())
            throw Status.fromThrowable(response.getMessage()).asException();

        String submissionId = response.getSubmissionId().getId();

        System.out.println("\nHere's your submission id: " + submissionId + ".");

        logger.info("Submission published successfully. Submission id is " + submissionId + ".");
    }

    private void getTranslationResult() throws StatusException
    {
        System.out.print("Please input the session id: ");
        String sessionId = scanner.nextLine();

        if(sessionId.isEmpty())
            throw Status.INVALID_ARGUMENT.withDescription("session id can't be empty").asException();

        System.out.print("Please input the submission id: ");
        String submissionId = scanner.nextLine();

        if(submissionId.isEmpty())
            throw Status.INVALID_ARGUMENT.withDescription("submission id can't be empty").asException();

        Session session = Session.newBuilder().setId(sessionId).build();
        Submission submission = Submission.newBuilder().setId(submissionId).build();

        Request request = Request.newBuilder().setSession(session).setSubmission(submission).build();
        ImageTextTranslated translation = blockingStub.requestImageTextTranslationResult(request);

        String translatedText = translation.getTranslatedText();
        String translatedFrom = translation.getTranslatedFrom();
        String translatedTo = translation.getTranslatedTo();

        String message = translatedText + ", FROM: " + translatedFrom + ", TO: " + translatedTo;
        System.out.println("\n" + message);

        translations.info(message);
    }

    public void start()
    {
        logger.info("Application started.");

        Scanner scanner = new Scanner(System.in);

        boolean done = false;

        System.out.println("Welcome!");

        while(!done)
        {
            System.out.println();
            System.out.println("Please pick one of the following options:");
            System.out.println("1: Sign in");
            System.out.println("2: Sign out");
            System.out.println("3: Submit translation request");
            System.out.println("4: Get translation result");
            System.out.println("5: Quit");
            System.out.println();

            System.out.print("Enter here your option: ");

            int operation = scanner.nextInt(10); scanner.nextLine();

            System.out.println();

            try {
                switch (operation) {
                    case 1:
                        signIn();
                        break;
                    case 2:
                        signOut();
                        break;
                    case 3:
                        submitTranslationRequest();
                        break;
                    case 4:
                        getTranslationResult();
                        break;
                    case 5:
                        done = true;
                        break;
                    default:
                        throw new Exception("Invalid operation! Please try again.");
                }
            } catch (Exception e) {
                System.out.println(e.getMessage());
                logger.error(e.getMessage());
            }
        }
    }

    public static void main(String... args)
    {
        String serverIp = DEFAULT_SERVER_IP;
        int serverPort = DEFAULT_SERVER_PORT;

        Pattern serverIpPattern = Pattern.compile("^-ip=((?:\\d{1,3}\\.){3}\\d+)$");
        Pattern portPattern = Pattern.compile("^-p=(\\d{1,5})$");

        for (String arg : args)
        {
            Matcher portMatcher = portPattern.matcher(arg);
            Matcher serverIpMatcher = serverIpPattern.matcher(arg);

            if(portMatcher.matches())
                serverPort = Integer.parseInt(portMatcher.group(1));
            else if(serverIpMatcher.matches())
                serverIp = serverIpMatcher.group(1);
        }

        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(serverIp, serverPort)
            .usePlaintext()
            .build();

        CNTextServiceBlockingStub blockingStub = CNTextServiceGrpc.newBlockingStub(channel);
        CNTextServiceStub stub = CNTextServiceGrpc.newStub(channel);

        CNTextClient client = new CNTextClient(blockingStub, stub);

        client.start();
    }
}
