package com.flowforge.task;

import com.flowforge.common.exception.UnsupportedMediaTypeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AttachmentTypeGate {

    /** How many leading bytes are needed to recognise every signature below. */
    public static final int HEADER_BYTES = 8;

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG =
            {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};
    /** OLE2 compound document — the container legacy {@code .doc} files use. */
    private static final byte[] OLE2 =
            {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
    /** ZIP local file header — the container {@code .docx} (and every other OOXML file) uses. */
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};

    private static final String PDF_TYPE = "application/pdf";
    private static final String JPEG_TYPE = "image/jpeg";
    private static final String PNG_TYPE = "image/png";
    private static final String DOC_TYPE = "application/msword";
    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** The types each recognised signature can legitimately be declared as. */
    private static final Map<String, Set<String>> TYPES_BY_SIGNATURE = Map.of(
            "PDF", Set.of(PDF_TYPE),
            "JPEG", Set.of(JPEG_TYPE),
            "PNG", Set.of(PNG_TYPE),
            "OLE2", Set.of(DOC_TYPE),
            "ZIP", Set.of(DOCX_TYPE));

    /** The file extension the platform stores each accepted type under. */
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            PDF_TYPE, ".pdf",
            JPEG_TYPE, ".jpg",
            PNG_TYPE, ".png",
            DOC_TYPE, ".doc",
            DOCX_TYPE, ".docx");

    private final Set<String> allowedTypes;

    public AttachmentTypeGate(
            @Value("${app.attachment.allowed-types:application/pdf,image/jpeg,image/png}")
            String allowedTypes
    ) {
        this.allowedTypes = Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(type -> type.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        log.info("Attachment types accepted: {}", this.allowedTypes);
    }

    public String accept(String declaredContentType, byte[] header) {
        String declared = normalise(declaredContentType);
        if (declared.isEmpty()) {
            throw new UnsupportedMediaTypeException(
                    "The upload declares no content type; allowed types are " + allowedTypes);
        }
        if (!allowedTypes.contains(declared)) {
            throw new UnsupportedMediaTypeException(
                    "Content type '%s' is not allowed; allowed types are %s"
                            .formatted(declared, allowedTypes));
        }

        String signature = signatureOf(header);
        if (signature == null) {
            throw new UnsupportedMediaTypeException(
                    "The uploaded bytes match no accepted file format, whatever the request declares");
        }
        if (!TYPES_BY_SIGNATURE.get(signature).contains(declared)) {
            throw new UnsupportedMediaTypeException(
                    "The uploaded bytes are %s, which is not '%s' as declared".formatted(
                            signature, declared));
        }
        return declared;
    }

    public String extensionFor(String acceptedContentType) {
        return EXTENSION_BY_TYPE.getOrDefault(normalise(acceptedContentType), ".bin");
    }

    /** The configured allowlist, for messages and tests. */
    public Set<String> allowedTypes() {
        return Set.copyOf(allowedTypes);
    }

    /** The signature name the header matches, or {@code null} when it matches none. */
    private String signatureOf(byte[] header) {
        byte[] bytes = header == null ? new byte[0] : header;
        if (startsWith(bytes, PDF)) {
            return "PDF";
        }
        if (startsWith(bytes, PNG)) {
            return "PNG";
        }
        if (startsWith(bytes, JPEG)) {
            return "JPEG";
        }
        if (startsWith(bytes, OLE2)) {
            return "OLE2";
        }
        if (startsWith(bytes, ZIP)) {
            return "ZIP";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        return Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Lower-cased media type without parameters — {@code "image/png; charset=x"} is {@code image/png}. */
    private static String normalise(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterStart = contentType.indexOf(';');
        String type = parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
