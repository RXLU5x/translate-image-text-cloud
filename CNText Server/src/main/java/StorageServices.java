import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.ByteString;

import java.io.IOException;

public class StorageServices
{
    public static final String DEFAULT_BUCKET_ID = "ipl_isel_cn_group_2_final_project_images";

    private final String projectId;
    private final Storage storage;

    public StorageServices(String projectId, GoogleCredentials credentials) {
        this.projectId = projectId;
        this.storage = StorageOptions
            .newBuilder()
            .setProjectId(projectId)
            .setCredentials(credentials)
            .build()
            .getService();
    }

    public void storeImage(BlobInfo imageInfo, ByteString image) {
        storage.create(imageInfo, image.toByteArray());
    }

    public ChunkingServices getChunkingServices(BlobInfo imageInfo) {
        return new ChunkingServices(storage.writer(imageInfo));
    }

    public String getProjectId() {
        return projectId;
    }

    public static class ChunkingServices
    {
        private final WriteChannel channel;

        private ChunkingServices(WriteChannel channel) {
            this.channel = channel;
        }

        public void storeImageChunk(ByteString chunk) throws IOException {
            channel.write(chunk.asReadOnlyByteBuffer());
        }

        public void closeChannel() throws IOException {
            channel.close();
        }
    }
}