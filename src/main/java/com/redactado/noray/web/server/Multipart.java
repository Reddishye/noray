package com.redactado.noray.web.server;

import com.redactado.noray.web.ui.FormData;
import com.redactado.noray.web.ui.Upload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A strict {@code multipart/form-data} parser over a body already bounded in size: at most {@value #MAX_PARTS} parts,
 * part headers at most 8 KiB, file names reduced to their last segment without control characters.
 */
final class Multipart {

    static final int MAX_PARTS = 64;
    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]{1,70})\"|([^;\\s]{1,70}))");
    private static final Pattern NAME = Pattern.compile("[; ]name=\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile("[; ]filename=\"([^\"]*)\"");

    private Multipart() {
    }

    static FormData parse(byte[] body, String contentType) {
        Matcher bm = BOUNDARY.matcher(contentType == null ? "" : contentType);
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data") || !bm.find()) {
            throw new IllegalArgumentException("not multipart/form-data");
        }
        byte[] delim = ("--" + (bm.group(1) != null ? bm.group(1) : bm.group(2))).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, List<String>> fields = new LinkedHashMap<>();
        List<Upload> files = new ArrayList<>();
        int pos = indexOf(body, delim, 0);
        if (pos < 0) throw new IllegalArgumentException("no boundary in body");
        int parts = 0;
        while (true) {
            pos += delim.length;
            if (pos + 2 <= body.length && body[pos] == '-' && body[pos + 1] == '-') break; // closing delimiter
            if (pos + 2 > body.length || body[pos] != '\r' || body[pos + 1] != '\n') throw new IllegalArgumentException("malformed delimiter");
            pos += 2;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), pos);
            if (headerEnd < 0 || headerEnd - pos > 8192) throw new IllegalArgumentException("malformed part headers");
            String headers = new String(body, pos, headerEnd - pos, StandardCharsets.UTF_8);
            int dataStart = headerEnd + 4;
            byte[] next = new byte[delim.length + 2];
            next[0] = '\r';
            next[1] = '\n';
            System.arraycopy(delim, 0, next, 2, delim.length);
            int dataEnd = indexOf(body, next, dataStart);
            if (dataEnd < 0) throw new IllegalArgumentException("unterminated part");
            if (++parts > MAX_PARTS) throw new IllegalArgumentException("too many parts");
            String disposition = header(headers, "content-disposition");
            Matcher nm = NAME.matcher(";" + disposition);
            if (!nm.find()) throw new IllegalArgumentException("part without a name");
            String name = nm.group(1);
            Matcher fm = FILENAME.matcher(";" + disposition);
            byte[] data = Arrays.copyOfRange(body, dataStart, dataEnd);
            if (fm.find()) {
                String filename = lastSegment(fm.group(1));
                if (!filename.isEmpty() || data.length > 0) {
                    files.add(new Upload(name, filename, header(headers, "content-type"), data));
                }
            } else {
                fields.computeIfAbsent(name, k -> new ArrayList<>()).add(new String(data, StandardCharsets.UTF_8));
            }
            pos = dataEnd + 2;
        }
        return new FormData(fields, files);
    }

    /** Browsers may send a full Windows path; keep the last segment and drop control and quote characters. */
    static String lastSegment(String raw) {
        String n = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        return n.replaceAll("[\\p{Cntrl}\"]", "").strip();
    }

    private static String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).strip().equalsIgnoreCase(name)) return line.substring(c + 1).strip();
        }
        return "";
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
