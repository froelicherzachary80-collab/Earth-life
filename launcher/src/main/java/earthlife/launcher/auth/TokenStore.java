package earthlife.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Stores the Microsoft refresh token.
 *  - Windows: encrypted with DPAPI (CurrentUser scope) via the built-in PowerShell/.NET ProtectedData API, so the
 *    file is useless to other Windows accounts or other PCs. No native library or extra dependency needed.
 *  - Other OSes: owner-only file permissions (rw-------).
 * File format: first line "dpapi" or "plain", second line base64 payload.
 */
public final class TokenStore {
    private final Path file;
    private final boolean windows;

    public TokenStore(Path file) { this(file, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")); }
    public TokenStore(Path file, boolean windows) { this.file = file; this.windows = windows; }

    public String load() throws IOException {
        if (!Files.exists(file)) return null;
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 2) return lines.isEmpty() || lines.get(0).isBlank() ? null : lines.get(0).trim();   // V0.1 plain file
        byte[] data = Base64.getDecoder().decode(lines.get(1).trim());
        if (lines.get(0).equals("dpapi")) {
            if (!windows) throw new IOException("Token was encrypted on Windows; sign in again on this computer.");
            data = dpapi(data, false);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public void save(String token) throws IOException {
        if (token == null) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] raw = token.getBytes(StandardCharsets.UTF_8);
        String kind = "plain";
        if (windows) {
            try { raw = dpapi(raw, true); kind = "dpapi"; }
            catch (IOException e) { System.err.println("[EarthLife] DPAPI unavailable, storing token with file permissions only: " + e.getMessage()); }
        }
        Files.writeString(file, kind + "\n" + Base64.getEncoder().encodeToString(raw) + "\n", StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
    }

    public void delete() throws IOException { Files.deleteIfExists(file); }

    /** Runs DPAPI through PowerShell. Data travels via stdin/stdout as base64, never on the command line. */
    private static byte[] dpapi(byte[] in, boolean protect) throws IOException {
        String op = protect ? "Protect" : "Unprotect";
        String script = "Add-Type -AssemblyName System.Security;"
                + "$b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim());"
                + "$r=[Security.Cryptography.ProtectedData]::" + op + "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
                + "[Console]::Out.Write([Convert]::ToBase64String($r))";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(false).start();
        try (var w = p.getOutputStream()) { w.write(Base64.getEncoder().encode(in)); }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) throw new IOException("powershell DPAPI " + op + " failed");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        return Base64.getDecoder().decode(out);
    }
}
