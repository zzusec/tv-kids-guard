package com.family.tvguard;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Enables KitKat's TLS 1.2 without changing certificates, hostname verification or cipher suites. */
final class UpdateTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    UpdateTlsSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    private Socket configure(Socket socket) throws IOException {
        if (!(socket instanceof SSLSocket)) {
            socket.close();
            throw new IOException("更新连接没有使用 TLS");
        }
        SSLSocket tls = (SSLSocket) socket;
        if (!Arrays.asList(tls.getSupportedProtocols()).contains("TLSv1.2")) {
            tls.close();
            throw new IOException("电视 TLS 不支持安全更新所需的 TLS 1.2");
        }
        tls.setEnabledProtocols(new String[]{"TLSv1.2"});
        return tls;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return configure(delegate.createSocket(socket, host, port, autoClose));
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, local, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, local, localPort));
    }
}
