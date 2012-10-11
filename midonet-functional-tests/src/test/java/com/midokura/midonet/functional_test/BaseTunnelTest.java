/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.File;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import akka.testkit.TestProbe;
import com.midokura.midolman.topology.LocalPortActive;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.cluster.DataClient;
import com.midokura.midonet.functional_test.utils.EmbeddedMidolman;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IPv4;
import com.midokura.packets.UDP;
import com.midokura.packets.IPacket;
import com.midokura.packets.GRE;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.lock.LockHelper;

import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.util.Waiters.sleepBecause;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;


public abstract class BaseTunnelTest {

    private final static Logger log = LoggerFactory.getLogger(BaseTunnelTest.class);

    final String TENANT_NAME = "tenant-tunnel-test";
    final String testConfigurationPath =
            "midolmanj_runtime_configurations/midolman-default.conf";

    // The two VMs that will send traffic across the bridge
    final IntIPv4 localVmIp = IntIPv4.fromString("192.168.231.1", 24);
    final IntIPv4 remoteVmIp = IntIPv4.fromString("192.168.231.2", 24);
    final MAC localVmMac = MAC.fromString("22:22:22:11:11:11");
    final MAC remoteVmMac = MAC.fromString("33:33:33:44:44:44");
    // The physical network
    final IntIPv4 physTapLocalIp = IntIPv4.fromString("10.245.215.1", 24);
    final IntIPv4 physTapRemoteIp = IntIPv4.fromString("10.245.215.2");
    final MAC physTapRemoteMac = MAC.fromString("aa:aa:aa:cc:cc:cc");
    MAC physTapLocalMac = null;

    TapWrapper vmTap, physTap;
    BridgePort localPort, remotePort;
    Bridge bridge;
    Host thisHost, remoteHost;
    UUID thisHostId, remoteHostId;

    MockMgmtStarter apiStarter;
    MidonetMgmt apiClient;
    EmbeddedMidolman midolman;

    DataClient dataClient;

    static LockHelper.Lock lock;
    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    @Before
    public void setUp() throws Exception {
        File testConfigFile = new File(testConfigurationPath);
        log.info("Starting embedded zookeper");
        int zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new MockMgmtStarter(zkPort);
        apiClient = new MidonetMgmt(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());
        dataClient = midolman.getDataClient();

        TestProbe probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);
        // FIXME
        Thread.sleep(10000);

        // Create a bridge with two ports
        log.info("Creating bridge and two ports.");
        bridge = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        localPort = bridge.addMaterializedPort().create();
        remotePort = bridge.addMaterializedPort().create();

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
                thisHostId = h.getId();
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        // create the tap for the vm and bind it to this host
        log.info("Creating tap for local vm and binding port to it");
        vmTap = new TapWrapper("vmTap");
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(localPort.getId()).create();

        // create a tap for the physical network and populate the neighbour
        // table with the remote host's ip address, to avoid ARPing
        log.info("Creating tap for the physical network");
        physTap = new TapWrapper("physTap");
        physTap.setIpAddress(physTapLocalIp);
        physTap.addNeighbour(physTapRemoteIp, physTapRemoteMac);
        physTapLocalMac = physTap.getHwAddr();
        assertNotNull("the physical tap's hw address", physTapLocalMac);

        // through the data client:
        log.info("Creating remote host");
        remoteHostId = UUID.randomUUID();
        com.midokura.midonet.cluster.data.host.Host remoteHost;
        remoteHost = new com.midokura.midonet.cluster.data.host.Host();
        InetAddress[] tmp =
            { InetAddress.getByName(physTapRemoteIp.toUnicastString()) };
        remoteHost.setName("remoteHost").
                setId(remoteHostId).
                setIsAlive(true).
                setAddresses(tmp);
        dataClient.hostsCreate(remoteHostId, remoteHost);

        log.info("binding remote port to remote host");
        dataClient.hostsAddVrnPortMapping(
                remoteHostId, remotePort.getId(), "nonExistent");

        log.info("adding remote host to portSet");
        dataClient.portSetsAddHost(bridge.getId(), remoteHostId);

        setUpTunnelZone();

        // TODO
        sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(vmTap);
        removeTapWrapper(physTap);
        stopEmbeddedMidolman();
        stopMidolmanMgmt(apiStarter);
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    private void matchUdpPacket(TapWrapper device,
                                    MAC fromMac, IntIPv4 fromIp,
                                    MAC toMac, IntIPv4 toIp,
                                    short udpSrc, short udpDst)
                                throws MalformedPacketException {
        byte[] received = device.recv();
        assertNotNull(String.format("Expected packet on %s", device.getName()),
                      received);
        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on " + device.getName() + ": " + eth.toString());
        matchUdpPacket(eth, fromMac, fromIp, toMac, toIp, udpSrc, udpDst);
    }

    private void matchUdpPacket(IPacket pkt,
                                    MAC fromMac, IntIPv4 fromIp,
                                    MAC toMac, IntIPv4 toIp,
                                    short udpSrc, short udpDst)
                                throws MalformedPacketException {
        assertTrue("packet is ethernet", pkt instanceof Ethernet);
        Ethernet eth = (Ethernet) pkt;

        assertEquals("source ethernet address",
            fromMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
            toMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
            fromIp.addressAsInt(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
            toIp.addressAsInt(), ipPkt.getDestinationAddress());

        assertTrue("payload is UDP", ipPkt.getPayload() instanceof UDP);
        UDP udpPkt = (UDP) ipPkt.getPayload();
        assertEquals("udp source port",
            udpSrc, udpPkt.getSourcePort());
        assertEquals("udp destination port",
            udpDst, udpPkt.getDestinationPort());
    }

    protected abstract IPacket matchTunnelPacket(TapWrapper device,
                                                 MAC fromMac, IntIPv4 fromIp,
                                                 MAC toMac, IntIPv4 toIp)
                                            throws MalformedPacketException;

    private void sendToTunnelAndVerifyEncapsulation()
            throws MalformedPacketException {
        byte[] pkt = PacketHelper.makeUDPPacket(localVmMac, localVmIp,
                                                remoteVmMac, remoteVmIp,
                                                (short) 2345, (short) 9876,
                                                "The Payload".getBytes());
        assertPacketWasSentOnTap(vmTap, pkt);

        log.info("Waiting for packet on physical tap");
        IPacket encap = matchTunnelPacket(physTap,
                                          physTapLocalMac, physTapLocalIp,
                                          physTapRemoteMac, physTapRemoteIp);
        matchUdpPacket(encap, localVmMac, localVmIp,
                              remoteVmMac, remoteVmIp,
                              (short) 2345, (short) 9876);
    }

    private void sendFromTunnelAndVerifyDecapsulation()
            throws MalformedPacketException {
        log.info("Injecting packet on physical tap");
        assertPacketWasSentOnTap(physTap, buildEncapsulatedPacket());

        log.info("Waiting for packet on vm tap");
        matchUdpPacket(vmTap, remoteVmMac, remoteVmIp,
                              localVmMac, localVmIp,
                              (short) 9876, (short) 2345);
    }

    @Test
    public void testTunnel() throws MalformedPacketException {
        // sent two packets through the tunnel
        sendToTunnelAndVerifyEncapsulation();
        sendToTunnelAndVerifyEncapsulation();

        // send two packets from the other side of the tunnel
        sendFromTunnelAndVerifyDecapsulation();
        sendFromTunnelAndVerifyDecapsulation();
    }

    protected abstract void setUpTunnelZone() throws Exception;

    protected abstract byte[] buildEncapsulatedPacket();

    protected void writeOnPacket(byte[] pkt, byte[] data, int offset) {
        for (int i = 0; i < data.length; i++)
            pkt[offset+i] = data[i];
    }
}
