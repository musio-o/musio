package com.musio.providers.qqmusic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginState;
import com.musio.model.LoginStatus;
import com.musio.model.ProviderType;
import com.musio.model.QQMusicCredential;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QQMusicAuthService {
    private static final Logger log = LoggerFactory.getLogger(QQMusicAuthService.class);

    private static final String APP_ID = "716027609";
    private static final String QQ_MUSIC_APP_ID = "100497308";
    private static final String DAID = "383";
    private static final String DEFAULT_QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 Edg/116.0.1938.54";
    private static final Pattern PTUI_CALLBACK = Pattern.compile("ptuiCB\\((.*?)\\)");
    private static final Pattern QRSIG_COOKIE = Pattern.compile("qrsig=([^;]+)");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QQMusicCredentialStore credentialStore;
    private final QQMusicSidecarClient sidecarClient;
    private final Cache<String, QQMusicLoginSession> loginSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();
    private final Cache<String, Boolean> sidecarLoginSessions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();

    @Autowired
    public QQMusicAuthService(QQMusicCredentialStore credentialStore, QQMusicSidecarClient sidecarClient) {
        this.credentialStore = credentialStore;
        this.sidecarClient = sidecarClient;
    }

    public QQMusicAuthService(QQMusicCredentialStore credentialStore) {
        this(credentialStore, null);
    }

    public LoginStartResult startLogin() {
        if (sidecarClient != null) {
            try {
                LoginStartResult result = sidecarClient.startLogin();
                sidecarLoginSessions.put(result.sessionId(), true);
                log.info("QQMUSIC_AUTH_START backend=sidecar sessionId={} state={}", result.sessionId(), result.state());
                return result;
            } catch (RuntimeException error) {
                log.warn("QQMUSIC_AUTH_START backend=sidecar status=fallback reason={}", message(error));
                // Fall back to the built-in Java QR flow while sidecar auth is being stabilized.
            }
        }
        QQMusicLoginSession session = QQMusicLoginSession.create();
        QRCode qrCode = generateQrCode(session);
        loginSessions.put(session.sessionId(), session);
        log.info("QQMUSIC_AUTH_START backend=java_fallback sessionId={} state={}", session.sessionId(), LoginState.NOT_SCANNED);

        return new LoginStartResult(
                session.sessionId(),
                ProviderType.QQMUSIC,
                LoginState.NOT_SCANNED,
                qrCode.dataUrl(),
                "Scan the QR code with QQ Music."
        );
    }

    public LoginStatus checkLogin(String sessionId) {
        if (sidecarClient != null && Boolean.TRUE.equals(sidecarLoginSessions.getIfPresent(sessionId))) {
            try {
                LoginStatus status = sidecarClient.checkLogin(sessionId);
                if (status.state() == LoginState.DONE || status.state() == LoginState.EXPIRED || status.state() == LoginState.FAILED) {
                    sidecarLoginSessions.invalidate(sessionId);
                }
                log.info(
                        "QQMUSIC_AUTH_STATUS backend=sidecar sessionId={} state={} credentialStored={}",
                        sessionId,
                        status.state(),
                        status.credentialStored()
                );
                return status;
            } catch (RuntimeException e) {
                sidecarLoginSessions.invalidate(sessionId);
                log.warn("QQMUSIC_AUTH_STATUS backend=sidecar status=failed sessionId={} reason={}", sessionId, message(e));
                return new LoginStatus(
                        sessionId,
                        ProviderType.QQMUSIC,
                        LoginState.FAILED,
                        credentialStore.exists(),
                        "QQ Music sidecar login status unavailable: " + message(e)
                );
            }
        }
        QQMusicLoginSession session = loginSessions.getIfPresent(sessionId);
        if (session == null) {
            return status(sessionId, LoginState.EXPIRED, "QR login session expired.");
        }

        LoginStatus status = checkLoginStatus(session);
        if (status.state() == LoginState.DONE || status.state() == LoginState.EXPIRED || status.state() == LoginState.FAILED) {
            loginSessions.invalidate(sessionId);
        }
        log.info(
                "QQMUSIC_AUTH_STATUS backend=java_fallback sessionId={} state={} credentialStored={}",
                sessionId,
                status.state(),
                status.credentialStored()
        );
        return status;
    }

    public LoginStatus logout() {
        loginSessions.invalidateAll();
        sidecarLoginSessions.invalidateAll();
        if (sidecarClient != null) {
            try {
                LoginStatus status = sidecarClient.logout();
                log.info("QQMUSIC_AUTH_LOGOUT backend=sidecar state={}", status.state());
                return status;
            } catch (RuntimeException error) {
                log.warn("QQMUSIC_AUTH_LOGOUT backend=sidecar status=fallback reason={}", message(error));
                // Fall back to deleting the shared credential file from Java.
            }
        }
        credentialStore.delete();
        log.info("QQMUSIC_AUTH_LOGOUT backend=java_fallback state={}", LoginState.LOGGED_OUT);
        return new LoginStatus("local", ProviderType.QQMUSIC, LoginState.LOGGED_OUT, false, "Logged out.");
    }

    private String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private QRCode generateQrCode(QQMusicLoginSession session) {
        HttpUrl url = HttpUrl.parse("https://ssl.ptlogin2.qq.com/ptqrshow").newBuilder()
                .addQueryParameter("appid", APP_ID)
                .addQueryParameter("e", "2")
                .addQueryParameter("l", "M")
                .addQueryParameter("s", "3")
                .addQueryParameter("d", "72")
                .addQueryParameter("v", "4")
                .addQueryParameter("t", String.valueOf(Math.random()))
                .addQueryParameter("daid", DAID)
                .addQueryParameter("pt_3rd_aid", QQ_MUSIC_APP_ID)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .get()
                .build();

        try (Response response = session.httpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IllegalStateException("Failed to request QQ Music QR code.");
            }

            String qrsig = extractQrsig(response.header("Set-Cookie", ""));
            if (qrsig == null || qrsig.isBlank()) {
                throw new IllegalStateException("QQ Music QR response did not include qrsig.");
            }

            session.setQrsig(qrsig);
            byte[] qrBytes = body.bytes();
            String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(qrBytes);
            return new QRCode(dataUrl);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to request QQ Music QR code.", e);
        }
    }

    private LoginStatus checkLoginStatus(QQMusicLoginSession session) {
        String qrsig = session.qrsig();
        if (qrsig == null || qrsig.isBlank()) {
            return status(session.sessionId(), LoginState.FAILED, "QR login session is missing qrsig.");
        }

        HttpUrl url = HttpUrl.parse("https://ssl.ptlogin2.qq.com/ptqrlogin").newBuilder()
                .addQueryParameter("u1", "https://graph.qq.com/oauth2.0/login_jump")
                .addQueryParameter("ptqrtoken", String.valueOf(hash33(qrsig)))
                .addQueryParameter("ptredirect", "0")
                .addQueryParameter("h", "1")
                .addQueryParameter("t", "1")
                .addQueryParameter("g", "1")
                .addQueryParameter("from_ui", "1")
                .addQueryParameter("ptlang", "2052")
                .addQueryParameter("action", "0-0-" + System.currentTimeMillis())
                .addQueryParameter("js_ver", "20102616")
                .addQueryParameter("js_type", "1")
                .addQueryParameter("pt_uistyle", "40")
                .addQueryParameter("aid", APP_ID)
                .addQueryParameter("daid", DAID)
                .addQueryParameter("pt_3rd_aid", QQ_MUSIC_APP_ID)
                .addQueryParameter("has_onekey", "1")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Referer", "https://xui.ptlogin2.qq.com/")
                .addHeader("Cookie", "qrsig=" + URLEncoder.encode(qrsig, StandardCharsets.UTF_8))
                .build();

        try (Response response = session.httpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return status(session.sessionId(), LoginState.FAILED, "Failed to check QR login status.");
            }

            String responseText = body.string();
            Matcher matcher = PTUI_CALLBACK.matcher(responseText);
            if (!matcher.find()) {
                return status(session.sessionId(), LoginState.FAILED, "QQ login status response was not recognized.");
            }

            String[] data = matcher.group(1).split(",", -1);
            if (data.length < 3) {
                return status(session.sessionId(), LoginState.FAILED, "QQ login status response was incomplete.");
            }

            int code = Integer.parseInt(unquote(data[0]));
            LoginState state = stateFromCode(code);
            if (state != LoginState.DONE) {
                return status(session.sessionId(), state, messageForState(state));
            }

            String loginUrl = unquote(data[2]);
            String sigx = extractParam(loginUrl, "ptsigx");
            String uin = extractParam(loginUrl, "uin");
            QQMusicCredential credential = authorizeQrLogin(session, uin, sigx);
            credentialStore.write(credential);
            return new LoginStatus(session.sessionId(), ProviderType.QQMUSIC, LoginState.DONE, true, "QQ Music login completed.");
        } catch (Exception e) {
            return status(session.sessionId(), LoginState.FAILED, "Failed to check QR login status: " + e.getMessage());
        }
    }

    private QQMusicCredential authorizeQrLogin(QQMusicLoginSession session, String uin, String sigx) throws IOException {
        String normalizedUin = normalizeUin(uin);
        String pSkey = checkSig(session, uin, sigx);
        String code = authorize(session, pSkey);
        return exchangeMusicCredential(session, normalizedUin, code);
    }

    private String checkSig(QQMusicLoginSession session, String uin, String sigx) throws IOException {
        HttpUrl checkSigUrl = HttpUrl.parse("https://ssl.ptlogin2.graph.qq.com/check_sig").newBuilder()
                .addQueryParameter("uin", uin)
                .addQueryParameter("pttype", "1")
                .addQueryParameter("service", "ptqrlogin")
                .addQueryParameter("nodirect", "0")
                .addQueryParameter("ptsigx", sigx)
                .addQueryParameter("s_url", "https://graph.qq.com/oauth2.0/login_jump")
                .addQueryParameter("ptlang", "2052")
                .addQueryParameter("ptredirect", "100")
                .addQueryParameter("aid", APP_ID)
                .addQueryParameter("daid", DAID)
                .addQueryParameter("j_later", "0")
                .addQueryParameter("low_login_hour", "0")
                .addQueryParameter("regmaster", "0")
                .addQueryParameter("pt_login_type", "3")
                .addQueryParameter("pt_aid", "0")
                .addQueryParameter("pt_aaid", "16")
                .addQueryParameter("pt_light", "0")
                .addQueryParameter("pt_3rd_aid", QQ_MUSIC_APP_ID)
                .build();

        Request request = new Request.Builder()
                .url(checkSigUrl)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .build();

        try (Response response = session.httpClient().newCall(request).execute()) {
            for (String header : response.headers("Set-Cookie")) {
                if (header.startsWith("p_skey=")) {
                    return header.split(";", 2)[0].split("=", 2)[1];
                }
            }
        }

        throw new IllegalStateException("QQ check_sig response did not include p_skey.");
    }

    private String authorize(QQMusicLoginSession session, String pSkey) throws IOException {
        RequestBody formBody = new FormBody.Builder()
                .add("response_type", "code")
                .add("client_id", QQ_MUSIC_APP_ID)
                .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%252F%252Fy.qq.com%252F")
                .add("scope", "get_user_info,get_app_friends")
                .add("state", "state")
                .add("switch", "")
                .add("from_ptlogin", "1")
                .add("src", "1")
                .add("update_auth", "1")
                .add("openapi", "1010_1030")
                .add("g_tk", String.valueOf(hash33(pSkey, 5381)))
                .add("auth_time", String.valueOf((System.currentTimeMillis() / 1000) * 1000))
                .add("ui", UUID.randomUUID().toString())
                .build();

        HttpUrl authorizeUrl = HttpUrl.get("https://graph.qq.com/oauth2.0/authorize");
        Request request = new Request.Builder()
                .url(authorizeUrl)
                .addHeader("Host", "graph.qq.com")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Connection", "keep-alive")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://y.qq.com/")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Cookie", session.cookieHeader(authorizeUrl))
                .post(formBody)
                .build();

        try (Response response = session.httpClient().newCall(request).execute()) {
            String location = response.header("Location", "");
            Matcher matcher = Pattern.compile("code=([^&]+)").matcher(location);
            if (!matcher.find()) {
                throw new IllegalStateException("QQ authorize response did not include code.");
            }
            return matcher.group(1);
        }
    }

    private QQMusicCredential exchangeMusicCredential(QQMusicLoginSession session, String uin, String code) throws IOException {
        Map<String, Object> comm = new HashMap<>();
        comm.put("cv", "13020508");
        comm.put("v", "13020508");
        comm.put("QIMEI36", DEFAULT_QIMEI36);
        comm.put("ct", "11");
        comm.put("tmeAppID", "qqmusic");
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("uid", uin);
        comm.put("tmeLoginType", "2");

        Map<String, Object> login = new HashMap<>();
        login.put("module", "music.login.LoginServer");
        login.put("method", "Login");
        login.put("param", Map.of("code", code));

        Map<String, Object> requestJson = new HashMap<>();
        requestJson.put("comm", comm);
        requestJson.put("music.login.LoginServer.Login", login);

        String json = objectMapper.writeValueAsString(requestJson);
        HttpUrl musicUrl = HttpUrl.get("https://u.y.qq.com/cgi-bin/musicu.fcg");
        RequestBody requestBody = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(musicUrl)
                .post(requestBody)
                .addHeader("Host", "u.y.qq.com")
                .addHeader("Accept", "*/*")
                .addHeader("Connection", "keep-alive")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "y.qq.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Cookie", session.cookieHeader(musicUrl))
                .build();

        try (Response response = session.httpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IllegalStateException("QQ Music credential exchange failed with HTTP " + response.code() + ".");
            }

            Map<String, Object> responseMap = objectMapper.readValue(body.string(), new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> moduleResponse = (Map<String, Object>) responseMap.getOrDefault(
                    "music.login.LoginServer.Login",
                    Map.of()
            );
            validateResponse(moduleResponse);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) moduleResponse.getOrDefault("data", moduleResponse);
            return credentialFromMap(data);
        }
    }

    private QQMusicCredential credentialFromMap(Map<String, Object> source) {
        Map<String, Object> extraFields = new HashMap<>(source);
        String musickey = stringValue(extraFields.remove("musickey"));
        int loginType = intValue(extraFields.remove("loginType"));
        if (loginType == 0) {
            loginType = musickey.startsWith("W_X") ? 1 : 2;
        }

        return new QQMusicCredential(
                stringValue(extraFields.remove("openid")),
                stringValue(extraFields.remove("refresh_token")),
                stringValue(extraFields.remove("access_token")),
                instantValue(extraFields.remove("expired_at")),
                stringValue(extraFields.remove("musicid")),
                musickey,
                stringValue(extraFields.remove("unionid")),
                stringValue(extraFields.remove("str_musicid")),
                stringValue(extraFields.remove("refresh_key")),
                stringValue(extraFields.remove("encryptUin")),
                loginType,
                extraFields
        );
    }

    private void validateResponse(Map<String, Object> data) {
        int code = intValue(data.get("code"));
        if (code != 0) {
            throw new IllegalStateException("QQ Music login failed with code " + code + ".");
        }
    }

    private LoginStatus status(String sessionId, LoginState state, String message) {
        return new LoginStatus(sessionId, ProviderType.QQMUSIC, state, credentialStore.exists(), message);
    }

    private static LoginState stateFromCode(int code) {
        return switch (code) {
            case 0 -> LoginState.DONE;
            case 65 -> LoginState.EXPIRED;
            case 66 -> LoginState.NOT_SCANNED;
            case 67 -> LoginState.SCANNED;
            default -> LoginState.FAILED;
        };
    }

    private static String messageForState(LoginState state) {
        return switch (state) {
            case NOT_SCANNED -> "Waiting for scan.";
            case SCANNED -> "QR code scanned. Confirm login on your device.";
            case EXPIRED -> "QR code expired.";
            case FAILED -> "QR login failed.";
            default -> state.name();
        };
    }

    private static String extractQrsig(String setCookie) {
        Matcher matcher = QRSIG_COOKIE.matcher(setCookie);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractParam(String url, String key) {
        Matcher matcher = Pattern.compile("[?&]" + key + "=([^&]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unquote(String value) {
        return value.replace("'", "").trim();
    }

    private static String normalizeUin(String uin) {
        String value = uin == null ? "" : uin;
        if (value.startsWith("o") || value.startsWith("O")) {
            return value.substring(1);
        }
        return value;
    }

    private static int hash33(String qrsig) {
        long hash = 0;
        for (int i = 0; i < qrsig.length(); i++) {
            hash += (hash << 5) + qrsig.charAt(i);
        }
        return (int) hash & 0x7fffffff;
    }

    private static int hash33(String value, int seed) {
        long hash = seed;
        for (int i = 0; i < value.length(); i++) {
            hash = (hash << 5) + hash + value.charAt(i);
        }
        return (int) (hash & 0x7fffffff);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static Instant instantValue(Object value) {
        if (value instanceof Number number) {
            long epoch = number.longValue();
            if (epoch > 10_000_000_000L) {
                return Instant.ofEpochMilli(epoch);
            }
            return Instant.ofEpochSecond(epoch);
        }
        return Instant.EPOCH;
    }

    private record QRCode(String dataUrl) {
    }

    private static final class QQMusicLoginSession {
        private final String sessionId;
        private final InMemoryCookieJar cookieJar;
        private final OkHttpClient httpClient;
        private String qrsig;

        private QQMusicLoginSession(String sessionId, InMemoryCookieJar cookieJar, OkHttpClient httpClient) {
            this.sessionId = sessionId;
            this.cookieJar = cookieJar;
            this.httpClient = httpClient;
        }

        static QQMusicLoginSession create() {
            InMemoryCookieJar cookieJar = new InMemoryCookieJar();
            OkHttpClient client = new OkHttpClient.Builder()
                    .followRedirects(false)
                    .connectTimeout(Duration.ofSeconds(30))
                    .readTimeout(Duration.ofSeconds(30))
                    .writeTimeout(Duration.ofSeconds(30))
                    .cookieJar(cookieJar)
                    .build();
            return new QQMusicLoginSession(UUID.randomUUID().toString(), cookieJar, client);
        }

        String sessionId() {
            return sessionId;
        }

        OkHttpClient httpClient() {
            return httpClient;
        }

        String qrsig() {
            return qrsig;
        }

        void setQrsig(String qrsig) {
            this.qrsig = qrsig;
        }

        String cookieHeader(HttpUrl url) {
            List<Cookie> cookies = cookieJar.loadForRequest(url);
            StringBuilder header = new StringBuilder();
            for (Cookie cookie : cookies) {
                if (cookie.value() == null || cookie.value().isBlank()) {
                    continue;
                }
                if (!header.isEmpty()) {
                    header.append("; ");
                }
                header.append(cookie.name()).append("=").append(cookie.value());
            }
            return header.toString();
        }
    }

    private static final class InMemoryCookieJar implements CookieJar {
        private final Map<String, Map<String, Cookie>> cookies = new ConcurrentHashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> responseCookies) {
            for (Cookie cookie : responseCookies) {
                String domain = normalizeDomain(cookie.domain());
                cookies.computeIfAbsent(domain, ignored -> new ConcurrentHashMap<>())
                        .put(cookie.name(), cookie);
            }
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> matched = new ArrayList<>();
            String host = normalizeDomain(url.host());
            while (!host.isBlank()) {
                Map<String, Cookie> domainCookies = cookies.get(host);
                if (domainCookies != null) {
                    matched.addAll(domainCookies.values());
                }
                int dot = host.indexOf('.');
                if (dot < 0) {
                    break;
                }
                host = host.substring(dot + 1);
            }
            return matched;
        }

        private static String normalizeDomain(String domain) {
            if (domain == null) {
                return "";
            }
            return domain.startsWith(".") ? domain.substring(1) : domain;
        }
    }
}
