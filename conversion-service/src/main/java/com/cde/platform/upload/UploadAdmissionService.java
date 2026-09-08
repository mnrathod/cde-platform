package com.cde.platform.upload;

import com.cde.platform.upload.MalwareScanner.ScanVerdict;
import com.cde.platform.upload.UploadedContentInspector.InspectedContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a file that has been streamed to disk may be admitted.
 *
 * <p>Quarantine-first, which is the ordering that matters: the file is written
 * where nothing can reach it, examined there, and only then promoted. Uploading
 * to its final location and checking afterwards leaves a window — usually
 * short, occasionally not — in which the file is referenced, downloadable, and
 * unexamined. That window is the whole vulnerability; closing it is what makes
 * this a control rather than a report.
 *
 * <p>A refused file is deleted rather than kept for inspection. Retaining
 * malware means storing it, backing it up, and replicating it, and the
 * signature in the audit record is what an investigation actually needs.
 */
@Service
public class UploadAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(UploadAdmissionService.class);

    private final UploadedContentInspector inspector;
    private final MalwareScanner scanner;
    private final UploadScanningPolicy policy;

    public UploadAdmissionService(UploadedContentInspector inspector,
                                  MalwareScanner scanner,
                                  UploadStagingProperties staging) {
        this.inspector = inspector;
        this.scanner = scanner;
        this.policy = staging.getScanning();
    }

    /**
     * Examines a quarantined file and either promotes it or deletes it.
     *
     * @param quarantined where the upload was streamed
     * @param destination where it belongs once it is clean
     * @param clientFileName what the upload called it, used only to choose
     *                       between formats detection cannot separate
     * @throws UploadRejectedException when the file must not be admitted. The
     *         file is already deleted by the time this is thrown.
     */
    public AdmittedFile admit(Path quarantined, Path destination, String clientFileName)
            throws IOException {
        InspectedContent content = inspector.inspect(quarantined, clientFileName);
        if (content.isRefused()) {
            deleteQuietly(quarantined);
            throw new UploadRejectedException(
                "This file was refused: " + content.refusalReason() + ".");
        }

        ScanVerdict verdict = scanFor(quarantined);
        if (!verdict.clean()) {
            deleteQuietly(quarantined);
            throw new UploadRejectedException(
                "This file was refused because a malware scan identified it as "
                + verdict.signature() + ".");
        }

        Files.createDirectories(destination.getParent());
        Files.move(quarantined, destination);

        return new AdmittedFile(destination, content.detectedType(), content.activeContent());
    }

    /**
     * @return a clean verdict, or throws. An unreachable scanner is resolved
     *         by policy rather than by assumption: REQUIRED refuses the upload,
     *         BEST_EFFORT admits it and says so loudly.
     */
    private ScanVerdict scanFor(Path quarantined) {
        if (!policy.scansAtAll()) {
            return ScanVerdict.safe();
        }
        if (!scanner.isOperational()) {
            return unscannable("no malware scanner is configured");
        }
        try {
            return scanner.scan(quarantined);
        } catch (IOException e) {
            log.warn("The malware scanner could not be reached", e);
            return unscannable("the malware scanner could not be reached");
        }
    }

    private ScanVerdict unscannable(String reason) {
        if (policy.requiresACleanVerdict()) {
            // Fail closed. On a shared Common Data Environment an unscanned
            // file is one every appointed party on the project will open.
            throw new UploadRejectedException(
                "Uploads cannot be accepted at the moment: " + reason
                + ", and this deployment requires every upload to be scanned. "
                + "Try again shortly.");
        }
        log.warn("Admitting an unscanned upload because {} and the policy is {}",
                 reason, policy);
        return ScanVerdict.safe();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Worth knowing about — a quarantine directory that fills with
            // refused files is a disk-exhaustion problem — but not worth
            // turning a correct refusal into a server error.
            log.warn("A refused upload could not be deleted from quarantine", e);
        }
    }

    /**
     * @param path          where the file now lives
     * @param detectedType  what the bytes say it is, not what it was called
     * @param activeContent whether a browser would execute it, so it must never
     *                      be served inline from the application's own origin
     */
    public record AdmittedFile(Path path, String detectedType, boolean activeContent) {}
}
