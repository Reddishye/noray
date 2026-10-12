package com.redactado.noray.web.server;

import com.redactado.noray.web.ui.FormData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MultipartTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void fieldsAndFilesAreSeparated() {
        FormData f = Multipart.parse(b("--X\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n"
                + "--X\r\nContent-Disposition: form-data; name=\"f\"; filename=\"../../etc/pa\u0001sswd\"\r\nContent-Type: x/y\r\n\r\nDATA\r\n--X--\r\n"),
                "multipart/form-data; boundary=X");
        assertEquals("1", f.get("a", ""));
        assertEquals("passwd", f.files().getFirst().filename());
        assertTrue(f.files().getFirst().hasSafeName());
        assertEquals("x/y", f.files().getFirst().declaredType());
    }

    @Test
    void malformedBodiesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Multipart.parse(b("x"), "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> Multipart.parse(b("--X\r\nno-headers"), "multipart/form-data; boundary=X"));
        assertThrows(IllegalArgumentException.class, () -> Multipart.parse(
                b("--X\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nunterminated"), "multipart/form-data; boundary=X"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < Multipart.MAX_PARTS + 1; i++) {
            many.append("--X\r\nContent-Disposition: form-data; name=\"p").append(i).append("\"\r\n\r\nv\r\n");
        }
        many.append("--X--\r\n");
        assertThrows(IllegalArgumentException.class, () -> Multipart.parse(b(many.toString()), "multipart/form-data; boundary=X"));
    }
}
