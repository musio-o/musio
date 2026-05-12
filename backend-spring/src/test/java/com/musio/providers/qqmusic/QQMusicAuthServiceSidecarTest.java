package com.musio.providers.qqmusic;

import com.musio.config.MusioConfigService;
import com.musio.model.LoginStartResult;
import com.musio.model.LoginState;
import com.musio.model.LoginStatus;
import com.musio.model.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QQMusicAuthServiceSidecarTest {
    @TempDir
    Path tempDir;

    @Test
    void delegatesSidecarLoginSessionToSidecarAuthEndpoints() {
        StubSidecarClient sidecarClient = new StubSidecarClient(config());
        QQMusicAuthService authService = new QQMusicAuthService(new QQMusicCredentialStore(config()), sidecarClient);

        LoginStartResult start = authService.startLogin();
        LoginStatus status = authService.checkLogin(start.sessionId());
        LoginStatus logout = authService.logout();

        assertEquals("sidecar-session", start.sessionId());
        assertEquals(LoginState.NOT_SCANNED, start.state());
        assertEquals(LoginState.SCANNED, status.state());
        assertEquals(LoginState.LOGGED_OUT, logout.state());
        assertEquals(1, sidecarClient.startLoginCalls);
        assertEquals(1, sidecarClient.checkLoginCalls);
        assertEquals(1, sidecarClient.logoutCalls);
    }

    private MusioConfigService config() {
        return new MusioConfigService(new MockEnvironment()
                .withProperty("musio.storage.home", tempDir.toString()));
    }

    private static final class StubSidecarClient extends QQMusicSidecarClient {
        private int startLoginCalls;
        private int checkLoginCalls;
        private int logoutCalls;

        private StubSidecarClient(MusioConfigService configService) {
            super(configService);
        }

        @Override
        public LoginStartResult startLogin() {
            startLoginCalls++;
            return new LoginStartResult(
                    "sidecar-session",
                    ProviderType.QQMUSIC,
                    LoginState.NOT_SCANNED,
                    "data:image/png;base64,stub",
                    "scan"
            );
        }

        @Override
        public LoginStatus checkLogin(String sessionId) {
            checkLoginCalls++;
            return new LoginStatus(sessionId, ProviderType.QQMUSIC, LoginState.SCANNED, false, "scanned");
        }

        @Override
        public LoginStatus logout() {
            logoutCalls++;
            return new LoginStatus("local", ProviderType.QQMUSIC, LoginState.LOGGED_OUT, false, "Logged out.");
        }
    }
}
