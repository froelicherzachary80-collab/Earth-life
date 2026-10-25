package earthlife.launcher.auth;

import earthlife.core.util.Json;
import earthlife.launcher.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Microsoft account sign-in for Minecraft Java, using the OAuth 2.0 device-code flow.
 * The launcher never sees the user's password: the user signs in on Microsoft's own page
 * (microsoft.com/link) and the launcher only receives tokens.
 *
 * Chain: Microsoft (XboxLive.signin) -> Xbox Live user token -> XSTS token -> Minecraft services
 * access token -> entitlement check (proves the account owns Java Edition) -> profile (UUID, name, skin).
 *
 * REQUIREMENTS (one-time, done by the developer, not the player):
 *  1. Register a "public client" app in Microsoft Entra (Azure) with "Allow public client flows" enabled,
 *     supported accounts = personal Microsoft accounts. Put its Application (client) ID in
 *     EARTHLIFE_MSA_CLIENT_ID or launcher.json "msaClientId".
 *  2. New client IDs must be approved by Mojang for the Minecraft services API, otherwise
 *     login_with_xbox returns 403. Apply via Mojang's form for third-party launchers.
 */
public final class MicrosoftAuth {
    static final String DEVICE_CODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    static final String TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String XBL = "https://user.auth.xboxlive.com/user/authenticate";
    static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    static final String MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore";
    static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    static final String SCOPE = "XboxLive.signin offline_access";

    public record Session(String username, String uuid, String accessToken, long expiresAtMs, String xuid, String skinUrl) {}
    public static final class NotOwned extends IOException { public NotOwned() { super("This Microsoft account does not own Minecraft: Java Edition."); } }

    private final String clientId;
    private final Path tokenFile;

    public MicrosoftAuth(String clientId, Path tokenFile) {
        if (clientId == null || clientId.isBlank())
            throw new IllegalStateException("No Microsoft app client ID configured (EARTHLIFE_MSA_CLIENT_ID). See launcher/README.md.");
        this.clientId = clientId; this.tokenFile = tokenFile;
    }

    /** Uses a stored refresh token if present, otherwise runs the device-code flow. {@code prompt} shows the code + URL to the user. */
    public Session login(Consumer<String> prompt) throws IOException, InterruptedException {
        String refresh = loadRefreshToken();
        Map<String, Object> msa = null;
        if (refresh != null) {
            try {
                msa = Http.postForm(TOKEN, Map.of("client_id", clientId, "grant_type", "refresh_token", "refresh_token", refresh, "scope", SCOPE));
            } catch (Http.HttpError e) { msa = null; }   // expired/revoked: fall through to interactive sign-in
        }
        if (msa == null) msa = deviceCode(prompt);
        saveRefreshToken((String) msa.get("refresh_token"));
        return minecraftSession((String) msa.get("access_token"));
    }

    private Map<String, Object> deviceCode(Consumer<String> prompt) throws IOException, InterruptedException {
        var dc = Http.postForm(DEVICE_CODE, Map.of("client_id", clientId, "scope", SCOPE));
        prompt.accept("To sign in, open " + dc.get("verification_uri") + " and enter code " + dc.get("user_code"));
        long interval = ((Number) dc.getOrDefault("interval", 5L)).longValue();
        long deadline = System.currentTimeMillis() + ((Number) dc.get("expires_in")).longValue() * 1000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000);
            try {
                return Http.postForm(TOKEN, Map.of("client_id", clientId, "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "device_code", (String) dc.get("device_code")));
            } catch (Http.HttpError e) {
                String err = e.body == null ? "" : Json.str(Json.obj(Json.parse(e.body)), "error");
                if ("authorization_pending".equals(err)) continue;
                if ("slow_down".equals(err)) { interval += 5; continue; }
                throw new IOException("Microsoft sign-in failed: " + err, e);
            }
        }
        throw new IOException("Sign-in code expired. Please try again.");
    }

    Session minecraftSession(String msAccessToken) throws IOException, InterruptedException {
        var xbl = Http.postJson(XBL, Map.of(
                "Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com", "RpsTicket", "d=" + msAccessToken),
                "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"), null);
        String xblToken = (String) xbl.get("Token");
        Map<String, Object> xsts;
        try {
            xsts = Http.postJson(XSTS, Map.of(
                    "Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(xblToken)),
                    "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"), null);
        } catch (Http.HttpError e) {
            throw new IOException(xstsMessage(e.body), e);
        }
        String uhs = (String) Json.obj(Json.arr(Json.obj(xsts.get("DisplayClaims")).get("xui")).get(0)).get("uhs");
        var mc = Http.postJson(MC_LOGIN, Map.of("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.get("Token")), null);
        String mcToken = (String) mc.get("access_token");
        long expires = System.currentTimeMillis() + ((Number) mc.get("expires_in")).longValue() * 1000;

        if (!ownsJava(Http.getJson(MC_ENTITLEMENTS, mcToken))) throw new NotOwned();
        Map<String, Object> profile;
        try { profile = Http.getJson(MC_PROFILE, mcToken); }
        catch (Http.HttpError e) { if (e.status == 404) throw new NotOwned(); throw e; }
        String skin = null;
        for (Object s : Json.arr(profile.get("skins"))) if ("ACTIVE".equals(Json.obj(s).get("state"))) skin = (String) Json.obj(s).get("url");
        return new Session((String) profile.get("name"), (String) profile.get("id"), mcToken, expires, uhs, skin);
    }

    /** Entitlement check: Java Edition ownership is "game_minecraft"/"product_minecraft" (Game Pass shows as product_game_pass_*). */
    public static boolean ownsJava(Map<String, Object> entitlements) {
        for (Object o : Json.arr(entitlements.get("items"))) {
            String n = (String) Json.obj(o).get("name");
            if ("game_minecraft".equals(n) || "product_minecraft".equals(n) || (n != null && n.startsWith("product_game_pass"))) return true;
        }
        return false;
    }

    public static String xstsMessage(String body) {
        try {
            long code = ((Number) Json.obj(Json.parse(body)).get("XErr")).longValue();
            if (code == 2148916233L) return "This Microsoft account has no Xbox profile. Sign in at minecraft.net once to create one.";
            if (code == 2148916235L) return "Xbox Live is not available in your country.";
            if (code == 2148916238L) return "This is a child account; it must be added to a Microsoft Family by an adult.";
            return "Xbox sign-in failed (XErr " + code + ").";
        } catch (RuntimeException e) { return "Xbox sign-in failed."; }
    }

    // ---- refresh token storage (DPAPI-encrypted on Windows, see TokenStore) ----
    private String loadRefreshToken() throws IOException {
        try { return new TokenStore(tokenFile).load(); }
        catch (IOException | IllegalArgumentException e) { return null; }      // unreadable: just sign in again
    }

    private void saveRefreshToken(String token) throws IOException { new TokenStore(tokenFile).save(token); }

    public void logout() throws IOException { new TokenStore(tokenFile).delete(); }
}
