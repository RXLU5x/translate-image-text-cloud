import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class StorageServices
{
    public static final String DEFAULT_BUCKET_ID = "ipl_isel_cn_group_2_final_project_images";

    private static final Logger logger = LogManager.getLogger(StorageServices.class);

    private final Storage storage;

    public StorageServices(
        String projectId,
        GoogleCredentials credentials
    ) {
        this.storage = StorageOptions
            .newBuilder()
            .setProjectId(projectId)
            .setCredentials(credentials)
            .build()
            .getService();
    }

    public void deleteImage(BlobId imageId) throws Exception {
        if(!storage.delete(imageId))
            throw new Exception("Image couldn't be deleted from the Google Cloud Storage");
    }

    public Image getImage(BlobId imageId) {
        String gcsPath = String.format("gs://%s/%s", imageId.getBucket(), imageId.getName());
        ImageSource imageSource = ImageSource.newBuilder().setGcsImageUri(gcsPath).build();

        return Image.newBuilder().setSource(imageSource).build();
    }

    public void downloadImage(BlobId imageId, Path filePath) {
        if(filePath.toFile().exists()) {
            logger.info("The image has already been downloaded.");
            return;
        }

        Blob image = storage.get(imageId);
        image.downloadTo(filePath);
    }

    public static BlobId getBlobId(String bucket, String blobName) {
        return BlobId.of(bucket, blobName);
    }
}