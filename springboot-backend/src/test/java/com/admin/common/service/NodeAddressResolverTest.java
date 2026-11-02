package com.admin.common.service;

import org.junit.jupiter.api.Test;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class NodeAddressResolverTest {
    // A real local DNS responder reproduces providers that omit addresses from ANY.
    private static final class Dns implements AutoCloseable {
        final DatagramSocket socket;
        final List<Integer> types=new CopyOnWriteArrayList<>();
        final Thread worker;
        Dns(boolean ipv4, boolean ipv6, boolean failV6) throws Exception {
            socket=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(() -> {
                while (!socket.isClosed()) try {
                    byte[] request=new byte[4096];DatagramPacket packet=new DatagramPacket(request,request.length);socket.receive(packet);
                    int end=12;while (request[end]!=0) end+=1+(request[end]&255);end++;
                    int type=((request[end]&255)<<8)|(request[end+1]&255);types.add(type);end+=4;
                    boolean failed=type==28 && failV6;
                    byte[] address=type==1 && ipv4 ? new byte[]{(byte)192,0,2,10}
                        : type==28 && ipv6 ? InetAddress.getByName("2001:db8::10").getAddress() : null;
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
                    out.write(request,0,2);out.writeShort(failed?0x8182:0x8180);out.writeShort(1);
                    out.writeShort(!failed && address!=null?1:0);out.writeShort(0);out.writeShort(0);out.write(request,12,end-12);
                    if (!failed && address!=null) {out.writeShort(0xc00c);out.writeShort(type);out.writeShort(1);out.writeInt(60);out.writeShort(address.length);out.write(address);}
                    byte[] reply=bytes.toByteArray();socket.send(new DatagramPacket(reply,reply.length,packet.getSocketAddress()));
                } catch (Exception failure) { if (!socket.isClosed()) throw new RuntimeException(failure); }
            },"fixture-dns");worker.setDaemon(true);worker.start();
        }
        NodeAddressResolver resolver() {
            return new NodeAddressResolver() {
                @Override InitialDirContext openContext(Hashtable<String,String> environment) throws NamingException {
                    environment.put("java.naming.provider.url","dns://127.0.0.1:"+socket.getLocalPort()+"/");
                    environment.put("com.sun.jndi.dns.timeout.initial","100");
                    return super.openContext(environment);
                }
            };
        }
        @Override public void close() throws Exception {socket.close();worker.join(1000);}
    }

    @Test void ipv4OnlyProviderDoesNotNeedAnySupportOrAnIpv6Record() throws Exception {
        try (Dns dns=new Dns(true,false,false)) {
            var result=dns.resolver().resolve("node.example");
            assertEquals("ok",result.status());assertEquals(List.of("192.0.2.10"),result.addresses());
            assertEquals(List.of(1,28),dns.types);
        }
    }
    @Test void ipv6OnlyAndDualStackProvidersAreReadUsingSeparateQueries() throws Exception {
        for (boolean ipv4:List.of(false,true)) try (Dns dns=new Dns(ipv4,true,false)) {
            var result=dns.resolver().resolve("node.example");
            assertEquals("ok",result.status());assertEquals(ipv4?2:1,result.addresses().size());
            assertTrue(result.addresses().stream().anyMatch(value->value.contains(":")));
            assertEquals(List.of(1,28),dns.types);
        }
    }
    @Test void FailedFamilyQueryCannotBecomeFalseAddressRemoval() throws Exception {
        try (Dns dns=new Dns(true,false,true)) {
            var result=dns.resolver().resolve("node.example");
            assertEquals("resolve_failed",result.status());assertTrue(result.addresses().isEmpty());
            assertFalse(dns.types.contains(255));
        }
    }
}
