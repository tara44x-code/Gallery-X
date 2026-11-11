package com.tara.nawagallery;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.List;

/**
 * Translates subtitle cues on the device with ML Kit. A language pack (~30 MB) is downloaded once;
 * after that everything works offline. Cues near the playhead are translated first.
 * All callbacks arrive on the main thread.
 */
public final class SubtitleTranslator {
    public interface Listener {
        void onCueTranslated(int index);
        void onProgress(int done, int total);
        void onFailed(String message);
    }

    public interface Result<T> { void accept(T value); }

    private final List<SubtitleParser.Cue> cues;
    private final Listener listener;
    private Translator translator;
    private boolean closed;
    private boolean running;
    private int focus;
    private int done;

    public SubtitleTranslator(List<SubtitleParser.Cue> cues, Listener listener) {
        this.cues = cues;
        this.listener = listener;
    }

    /** Guesses the subtitle's language from its first lines; answers an ML Kit code such as "en" or "ar". */
    public static void detectLanguage(List<SubtitleParser.Cue> cues, Result<String> result) {
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < cues.size() && sample.length() < 1500; i += Math.max(1, cues.size() / 40)) {
            sample.append(cues.get(i).text.replace('\n', ' ')).append(". ");
        }
        String text = sample.toString();
        String fallback = containsArabic(text) ? TranslateLanguage.ARABIC : TranslateLanguage.ENGLISH;
        LanguageIdentifier identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(text)
                .addOnSuccessListener(code -> {
                    String language = code == null || "und".equals(code) ? null : TranslateLanguage.fromLanguageTag(code);
                    result.accept(language == null ? fallback : language);
                    identifier.close();
                })
                .addOnFailureListener(error -> {
                    result.accept(fallback);
                    identifier.close();
                });
    }

    public static void isPackReady(String language, Result<Boolean> result) {
        RemoteModelManager.getInstance()
                .isModelDownloaded(new TranslateRemoteModel.Builder(language).build())
                .addOnSuccessListener(result::accept)
                .addOnFailureListener(error -> result.accept(false));
    }

    /** Downloads the language packs if needed, then translates every cue. */
    public void start(String source, String target) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build();
        translator = Translation.getClient(options);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(ignored -> {
                    if (closed) return;
                    running = true;
                    next();
                })
                .addOnFailureListener(error -> {
                    if (!closed) listener.onFailed("تعذر تنزيل حزمة اللغة. تأكد من الإنترنت وحاول مرة ثانية.");
                });
    }

    /** Moves translation priority to the cue being watched. */
    public void focus(int index) {
        focus = Math.max(0, index);
    }

    private void next() {
        if (closed || !running) return;
        int index = pick();
        if (index < 0) {
            running = false;
            listener.onProgress(cues.size(), cues.size());
            return;
        }
        SubtitleParser.Cue cue = cues.get(index);
        translator.translate(cue.text.replace('\n', ' '))
                .addOnSuccessListener(text -> {
                    if (closed) return;
                    cue.translated = text == null || text.trim().isEmpty() ? cue.text : text.trim();
                    done++;
                    listener.onCueTranslated(index);
                    if (done % 20 == 0) listener.onProgress(done, cues.size());
                    next();
                })
                .addOnFailureListener(error -> {
                    if (closed) return;
                    cue.translated = cue.text; // keep the original instead of retrying forever
                    done++;
                    next();
                });
    }

    private int pick() {
        int start = Math.min(focus, Math.max(0, cues.size() - 1));
        for (int i = start; i < cues.size(); i++) if (cues.get(i).translated == null) return i;
        for (int i = 0; i < start; i++) if (cues.get(i).translated == null) return i;
        return -1;
    }

    public void close() {
        closed = true;
        running = false;
        if (translator != null) translator.close();
        translator = null;
    }

    private static boolean containsArabic(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '؀' && c <= 'ۿ') return true;
        }
        return false;
    }
}
