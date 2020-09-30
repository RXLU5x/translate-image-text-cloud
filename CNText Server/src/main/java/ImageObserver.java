import com.google.cloud.storage.BlobInfo;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.TopicName;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import ipl.isel.cn.group2.contract.Image;
import ipl.isel.cn.group2.contract.Image.Metadata;
import ipl.isel.cn.group2.contract.Submission;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageObserver implements StreamObserver<Image>
{
    public static volatile AtomicInteger numberOfPremiumSessions = new AtomicInteger();

    final private StreamObserver<Submission> responseObserver;

    final private FirestoreServices firestoreServices;
    final private StorageServices storageServices;

    private StorageServices.ChunkingServices chunkingServices;
    private PublisherServices publisherServices;

    private Submission submission;
    private Metadata metadata;
    private BlobInfo blobInfo;

    private final String bucketId;

    private long readBytes = 0L;

    private static final Logger logger = LogManager.getLogger(ImageObserver.class);

    public ImageObserver(
        StreamObserver<Submission> responseObserver,
        FirestoreServices firestoreServices,
        StorageServices storageServices,
        String bucketId
    ) {
        this.responseObserver = responseObserver;
        this.firestoreServices = firestoreServices;
        this.storageServices = storageServices;
        this.bucketId = bucketId;
    }

    @Override
    public void onNext(Image image)
    {
        try {
            if (image.hasMetadata()) {
                metadata = image.getMetadata();

                String sessionId = metadata.getSession().getId();

                String imageName = metadata.getName();
                String imageExtension = "." + imageName.split("\\.")[1];

                String serviceLevel = firestoreServices.validateSession(sessionId);

                if(serviceLevel.compareToIgnoreCase("premium") == 0)
                    numberOfPremiumSessions.incrementAndGet();

                String submissionId = firestoreServices.storeSubmission(sessionId);

                blobInfo = BlobInfo
                    .newBuilder(bucketId, submissionId + imageExtension)
                    .build();

                TopicName topicName = TopicName.newBuilder()
                    .setProject(storageServices.getProjectId())
                    .setTopic(serviceLevel + "-ocr")
                    .build();

                publisherServices = new PublisherServices(topicName);
                submission = Submission.newBuilder().setId(submissionId).build();
            }

            else
            {
                ByteString chunk = image.getChunk();

                if (metadata.getSize() <= 1_000_000)
                    storageServices.storeImage(blobInfo, chunk);
                else {
                    if (readBytes == 0L)
                        chunkingServices = storageServices.getChunkingServices(blobInfo);
                    else {
                        chunkingServices.storeImageChunk(chunk);
                        readBytes += chunk.size();

                        if (readBytes == metadata.getSize())
                            chunkingServices.closeChannel();
                    }
                }
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(ex.getMessage()).asException());
        }
    }

    @Override
    public void onError(Throwable throwable) {
        StatusException ex = Status.fromThrowable(throwable).asException();
        logger.error(ex.getMessage());
    }

    @Override
    public void onCompleted()
    {
        try {
            HashMap<String, String> attributes = new HashMap<>();
            attributes.put("submissionId", submission.getId());
            attributes.put("to", metadata.getTranslateTo());

            logger.info("Image " + metadata.getName() + " received successfully. It's stored in the Google Cloud Storage as " + blobInfo.getBucket() + "/" + blobInfo.getName());

            publisherServices.publish(blobInfo.getName(), attributes);

            responseObserver.onNext(submission);
            responseObserver.onCompleted();
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            responseObserver.onError(Status.INTERNAL.withDescription(ex.getMessage()).asException());
        }
    }
}