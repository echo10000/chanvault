package dev.chanvault.reader;

/** Executable JVM smoke tests for download URL validation, no Android runtime required. */
public final class MediaAddressValidationTest {
    private static void check(String url, boolean expected) {
        boolean actual = MediaAddress.isValidMediaUrl(url);
        if (actual != expected)
            throw new AssertionError("Validation unexpected for: " + url + " expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] args) {
        check("https://i.4cdn.org/gif/31323406.webm", true);
        check("https://i.4cdn.org/gif/1234567890123.mp4", true);
        check("https://i.4cdn.org/g/1234567890123456.jpg", true);
        check("https://i.4cdn.org/wg/123456789012345678901234.png", true);
        check("https://i.4cdn.org/gif/12345.gif", true);
        check("https://evil.example/gif/1234567890123.webm", false);
        check("http://i.4cdn.org/gif/1234567890123.webm", false);
        check("https://i.4cdn.org/gif/1234567890123.webm?x=1", false);
        check("https://i.4cdn.org/gif/1234567890123.webm#frag", false);
        check("https://i.4cdn.org/gif/1234.webm", false);
        check("https://i.4cdn.org/gif/1234567890123456789012345.webm", false);
        check("https://i.4cdn.org/gif/1234567890123.exe", false);
        check("https://i.4cdn.org/gif/1234567890123.webm/extra", false);
        check("https://i.4cdn.org/%2e%2e/1234567890123.mp4", false);
        check("https://i.4cdn.org/gif/1234567890123.WEBM", false);
        check(null, false);
        if (!MediaAddress.isValidBoard("gif") || MediaAddress.isValidBoard("../gif"))
            throw new AssertionError("Invalid board handling");
        System.out.println("MediaAddress validation: 18 checks passed");
    }
}
