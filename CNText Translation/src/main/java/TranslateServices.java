import com.google.cloud.translate.Detection;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;

public class TranslateServices
{
    private Translate translate;

    public TranslateServices() {
        translate = TranslateOptions.getDefaultInstance().getService();
    }

    public String translateText(String text, String from, String to) {
        Translation translation = translate.translate(text, Translate.TranslateOption.sourceLanguage(from), Translate.TranslateOption.targetLanguage(to));

        return translation.getTranslatedText();
    }

    public String detectLanguage(String text) {
        String detectedLanguage = "und";
        Detection detection = translate.detect(text);
        detectedLanguage = detection.getLanguage();

        return detectedLanguage;
    }
}
