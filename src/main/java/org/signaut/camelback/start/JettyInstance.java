/*
Copyright (c) 2010, Jesper André Lyngesen Pedersen
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 - Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

 - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.signaut.camelback.start;

import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletContext;

import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Authenticator.AuthConfiguration;
import org.eclipse.jetty.security.Authenticator.Factory;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.signaut.camelback.configuration.CamelbackConfig;
import org.signaut.common.hazelcast.HazelcastFactory;
import org.signaut.couchdb.impl.CouchDbAuthenticatorImpl;
import org.signaut.jetty.deploy.providers.couchdb.CouchDbAppProvider;
import org.signaut.jetty.deploy.providers.couchdb.CouchDbAppProvider.SessionManagerProvider;
import org.signaut.jetty.server.security.CouchDbLoginService;
import org.signaut.jetty.server.security.authentication.CouchDbSSOAuthenticator;
import org.signaut.jetty.server.session.HazelcastSessionIdManager;
import org.signaut.jetty.server.session.HazelcastSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;

class JettyInstance {
    private final CamelbackConfig config;
    private final Server server ;
    private final HazelcastFactory hazelcastFactory = new HazelcastFactory();
    private final Authenticator.Factory authenticatorFactory;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public JettyInstance(CamelbackConfig config) {
        this.config = config;
        //Keep the minimum threadcount low so we can get rid of old threads (with potential thread local values).
        final QueuedThreadPool threadPool = new QueuedThreadPool(config.getThreadPoolSize(), 1);
        this.server = new Server(threadPool);
        authenticatorFactory = new Factory() {
            final CouchDbSSOAuthenticator authenticator =
                new CouchDbSSOAuthenticator(new CouchDbAuthenticatorImpl(JettyInstance.this.config.getLoginConfig()
                                                                         .getAuthenticationUrl()));
            @Override
            public Authenticator getAuthenticator(Server server, ServletContext context, AuthConfiguration configuration,
                    IdentityService identityService, LoginService loginService) {
                return authenticator;
            }
        };
    }

    public void start() {
        final long startingTime = System.currentTimeMillis();
        setConnectors(server);

        final HandlerCollection handlers = new HandlerCollection();
        final ContextHandlerCollection contextHandlers = new ContextHandlerCollection();
        final Handler defaultHandler = new DefaultHandler();
        handlers.setHandlers(new Handler[] { contextHandlers, defaultHandler });
        server.setHandler(handlers);
        
        final HazelcastInstance hazelcastInstance = hazelcastFactory.loadHazelcastInstance(config.getHazelcastConfig(), getClass()); 
        
        final ConcurrentMap<String, String> activeUsers = hazelcastInstance.getMap("signaut.activeUsers");
        // Authentication
        final CouchDbLoginService couchDbLoginService = new CouchDbLoginService("couchdb_realm",
                new CouchDbAuthenticatorImpl(config.getLoginConfig().getAuthenticationUrl()),
                activeUsers);
                
        server.addBean(couchDbLoginService);
        server.addBean(authenticatorFactory);
        
        // Session manager
        final HazelcastSessionIdManager clusterSessionIdManager = 
                new HazelcastSessionIdManager(server, hazelcastInstance);

        final SessionManagerProvider sessionManagerProvider = new SessionManagerProvider() {
            @Override
            public SessionManager get() {
                return new HazelcastSessionManager(clusterSessionIdManager);
            }
        };
       
        // Deployment handling
        final DeploymentManager deploymentManager = new DeploymentManager();
        deploymentManager.setContexts(contextHandlers);
        server.addBean(deploymentManager);
        deploymentManager.addAppProvider(new CouchDbAppProvider().setCouchDeployerProperties(config.getDeployerConfig())
                                                                .setAuthenticatorFactory(authenticatorFactory)
                                                                .setLoginService(couchDbLoginService)
                                                                .setSessionManagerProvider(sessionManagerProvider));

        

        server.setStopAtShutdown(true);


        // Now start server
        try {
            server.start();
            log.info(String.format("Started camelback in %d milliseconds", System.currentTimeMillis()-startingTime));
        } catch (Exception e) {
            throw new IllegalStateException("While starting jetty", e);
        }

    }


    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("While stopping server", e);
        }
    }

    private void setConnectors(Server server) {
    	final HttpConfiguration httpConfig = new HttpConfiguration();
    	httpConfig.setSecureScheme("https");
    	httpConfig.setSecurePort(config.getSecurePort());
    	final ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
    	httpConnector.setPort(config.getPort());
    	httpConnector.setName("http");
        server.addConnector(httpConnector);

        if (config.getSslConfig() != null
                && config.getSslConfig().getKeystore()!=null) {
            final HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());
            final SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(config.getSslConfig().getKeystore());
            sslContextFactory.setKeyStorePassword(config.getSslConfig().getKeystorePassword());
            sslContextFactory.setKeyManagerPassword(config.getSslConfig().getKeyManagerPassword());
            sslContextFactory.setTrustStorePath(config.getSslConfig().getTruststore());
            sslContextFactory.setTrustStorePassword(config.getSslConfig().getTruststorePassword());
            sslContextFactory.setExcludeCipherSuites(new String[] {
                    "SSL_RSA_WITH_DES_CBC_SHA",
                    "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                    "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                    "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA"
            });
            final ServerConnector httpsConnector = new ServerConnector(server,
                new SslConnectionFactory(sslContextFactory,"http/1.1"),
                new HttpConnectionFactory(httpsConfig));
            httpsConnector.setPort(config.getSecurePort());
            httpsConnector.setName("https");
        	server.addConnector(httpsConnector);
        }

    }

}
