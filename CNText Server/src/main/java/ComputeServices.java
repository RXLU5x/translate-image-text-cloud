import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.Operation;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class ComputeServices
{
    private static final Logger logger = LogManager.getRootLogger();

    public static final String DEFAULT_PREMIUM_OCR_INSTANCE_GROUP_ID = "instance-group-premium-ocr";
    public static final String DEFAULT_PREMIUM_TRANSLATION_INSTANCE_GROUP_ID = "instance-group-premium-translation";

    private final String projectId;
    private final String zoneId;

    private final Compute compute;

    public ComputeServices(
        String projectId,
        GoogleCredentials credentials,
        String zoneId
    ) throws GeneralSecurityException, IOException {
        this.projectId = projectId;
        this.zoneId = zoneId;

        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JacksonFactory jsonFactory = JacksonFactory.getDefaultInstance();
        HttpRequestInitializer requestInit = new HttpCredentialsAdapter(credentials);

        compute = new Compute.Builder(transport, jsonFactory, requestInit)
            .setApplicationName("CN Text Server")
            .build();
    }

    public Operation.Error resizeInstanceGroup(String instanceGroupId, int newSize) throws IOException, InterruptedException
    {
        Compute.InstanceGroupManagers.Resize request = compute
            .instanceGroupManagers()
            .resize(projectId, zoneId, instanceGroupId, newSize);

        try {
            Operation.Error error = waitOperation(request.execute());
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw e;
        }

        return null;
    }

    private Operation.Error waitOperation(Operation op) throws IOException, InterruptedException
    {
        String zone = op.getZone();

        if (zone != null) {
            String[] bits = zone.split("/");
            zone = bits[bits.length - 1];
        }

        while (!op.getStatus().equals("DONE")) {
            Thread.sleep(1000);

            Compute.ZoneOperations.Get get = compute
                .zoneOperations()
                .get(projectId, zone, op.getName());

            op = get.execute();
        }

        return op.getError();
    }
}
