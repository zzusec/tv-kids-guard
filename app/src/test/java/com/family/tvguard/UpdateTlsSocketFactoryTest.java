package com.family.tvguard;

import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
public class UpdateTlsSocketFactoryTest {
    @Test
    public void kitkatTransportEnablesTls12OnlyAndRetainsHostnameVerifier() throws Exception {
        Method open = UpdateManager.class.getDeclaredMethod("openConnection", URL.class);
        open.setAccessible(true);
        HttpsURLConnection connection = (HttpsURLConnection) open.invoke(null, new URL(UpdateManager.METADATA_URL));
        assertSame(HttpsURLConnection.getDefaultHostnameVerifier(), connection.getHostnameVerifier());
        assertEquals(Build.VERSION.SDK_INT < 21, connection.getSSLSocketFactory() instanceof UpdateTlsSocketFactory);
        if (Build.VERSION.SDK_INT < 21) {
            try (SSLSocket socket = (SSLSocket) connection.getSSLSocketFactory().createSocket()) {
                assertArrayEquals(new String[]{"TLSv1.2"}, socket.getEnabledProtocols());
            }
        }
        connection.disconnect();
    }

    @Test
    public void allSocketOverloadsEnableTls12WithoutChangingCipherSuitesOrLosingHost() throws Exception {
        FixtureFactory delegate = new FixtureFactory();
        UpdateTlsSocketFactory factory = new UpdateTlsSocketFactory(delegate);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Socket[] sockets = {factory.createSocket(), factory.createSocket("github.com", 443),
                factory.createSocket("github.com", 443, loopback, 0), factory.createSocket(loopback, 443),
                factory.createSocket(loopback, 443, loopback, 0), factory.createSocket(new Socket(), "github.com", 443, true)};
        for (Socket socket : sockets) {
            SSLSocket tls = (SSLSocket) socket;
            assertArrayEquals(new String[]{"TLSv1.2"}, tls.getEnabledProtocols());
            assertArrayEquals(delegate.enabledCiphers, tls.getEnabledCipherSuites());
            socket.close();
        }
        assertEquals("github.com", delegate.layeredHost);
        assertArrayEquals(delegate.getDefaultCipherSuites(), factory.getDefaultCipherSuites());
        assertArrayEquals(delegate.getSupportedCipherSuites(), factory.getSupportedCipherSuites());
    }

    @Test
    public void unsupportedTls12FailsClosedInsteadOfDowngrading() throws Exception {
        FixtureFactory delegate = new FixtureFactory();
        delegate.next = new OldTlsSocket();
        IOException error = assertThrows(IOException.class, () -> new UpdateTlsSocketFactory(delegate).createSocket());
        assertTrue(error.getMessage().contains("TLS 1.2"));
        assertTrue(delegate.next.isClosed());
    }

    @Test
    public void nonTlsSocketFailsClosed() throws Exception {
        FixtureFactory delegate = new FixtureFactory();
        delegate.next = new Socket();
        assertThrows(IOException.class, () -> new UpdateTlsSocketFactory(delegate).createSocket());
        assertTrue(delegate.next.isClosed());
    }

    private static class FixtureFactory extends SSLSocketFactory {
        final SSLSocketFactory real;
        final String[] enabledCiphers;
        Socket next;
        String layeredHost;

        FixtureFactory() throws Exception {
            real = SSLContext.getDefault().getSocketFactory();
            try (SSLSocket socket = (SSLSocket) real.createSocket()) {
                enabledCiphers = socket.getEnabledCipherSuites();
            }
        }

        private Socket socket() throws IOException {
            if (next != null) return next;
            SSLSocket socket = (SSLSocket) real.createSocket();
            socket.setEnabledProtocols(new String[]{"TLSv1"});
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return real.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return real.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return socket(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            layeredHost = host;
            return socket();
        }
        @Override public Socket createSocket(String host, int port) throws IOException { return socket(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return socket(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return socket(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return socket(); }
    }

    private static final class OldTlsSocket extends SSLSocket {
        @Override public String[] getSupportedProtocols() { return new String[]{"TLSv1"}; }
        @Override public String[] getEnabledProtocols() { return new String[]{"TLSv1"}; }
        @Override public void setEnabledProtocols(String[] protocols) {}
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) {}
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void startHandshake() {}
        @Override public void setUseClientMode(boolean mode) {}
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) {}
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) {}
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean enabled) {}
        @Override public boolean getEnableSessionCreation() { return true; }
    }
}
