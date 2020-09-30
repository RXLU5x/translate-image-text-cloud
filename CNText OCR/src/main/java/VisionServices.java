import com.google.cloud.vision.v1.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class VisionServices
{
    public static final Feature.Type DEFAULT_FEATURE_TYPE = Feature.Type.TEXT_DETECTION;

    private static final Logger logger = LogManager.getLogger(VisionServices.class);

    public String detectImageText(Image image, Feature feature)
    {
        String detectedText = null;

        try (ImageAnnotatorClient client = ImageAnnotatorClient.create())
        {
            AnnotateImageRequest request = AnnotateImageRequest
                .newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build();

            List<AnnotateImageRequest> requests = new ArrayList<>();
            requests.add(request);

            AnnotateImageResponse response = client
                .batchAnnotateImages(requests)
                .getResponses(0);

            if (response == null || !response.hasFullTextAnnotation()) {
                logger.info("No text was found in this image.");
                return null;
            }

            if (response.hasError())
                throw new Exception(response.getError().getMessage());

            detectedText = response.getFullTextAnnotation().getText();
        } catch (Exception e) {
            logger.error(e.getMessage());
        }

        return detectedText;
    }
}
