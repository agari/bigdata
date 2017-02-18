package com.big.data.kafka.unit;

import org.apache.curator.test.TestingServer;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;

public class EmbeddedZookeeper extends ExternalResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedZookeeper.class);
    private TestingServer zkServer;
    private EmbeddedZookeeperConfig config;

    @Override
    protected void before() throws Throwable {
        config = new EmbeddedZookeeperConfig();
        // Random free port being used

        zkServer = new TestingServer(config.zookeperPort, true);
    }

    @Override
    protected void after() {
        if (null != zkServer) {
            try {
                zkServer.stop();
            } catch (IOException e) {
                LOGGER.info("Error while Zookeeper Shutdown ", e);
            }
        }
    }

    public EmbeddedZookeeperConfig getConfig() {
        return config;
    }

    /**
     * configuration for zookeper.
     */
    public class EmbeddedZookeeperConfig {

        private int zookeperPort;
        private String zkstring;

        public EmbeddedZookeeperConfig() {
            zookeperPort = FreeRandomPort.generateRandomPort();
            zkstring = InetAddress.getLoopbackAddress().getHostAddress() + ":" + zookeperPort;
        }

        public int getZookeperPort() {
            return zookeperPort;
        }

        public String getZkstring() {
            return zkstring;
        }

    }

}
