package org.oscchina.testonsite.rtp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.TreeSet;
import org.apache.log4j.Logger;

public class RtpSendRecv
  implements Runnable
{
  public static final long SSRC_MAGIC = -555684209L;
  public static final int RTP_MAX_SIZE = 1500;
  public static final int NANO_AS_MS = 1000000;
  private final int portMin;
  private final int portMax;
  private static Logger log = Logger.getLogger("RtpSender");
  private DatagramSocket socket;
  private InetAddress localAddr;
  private InetAddress remoteAddr;
  private int remotePort;
  private ArrayList<Long> sentTime;
  private ArrayList<Long> recvTime;
  private boolean running;
  public volatile int seqn;
  public volatile boolean success;
  public volatile String diagnostics;

  public RtpSendRecv(int portMin, int portMax)
  {
    this.portMin = portMin;
    this.portMax = portMax;
    this.seqn = 0;
    this.sentTime = new ArrayList(2500);
    this.recvTime = new ArrayList(2500);
  }

  public void createSocket(String host, int remotePort) throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    this.localAddr = resolveAddress("0.0.0.0");
    this.remoteAddr = resolveAddress(host);
    this.remotePort = remotePort;

    int port = this.portMin; if (port < this.portMax) {
      try {
        this.socket = new DatagramSocket(port, this.localAddr);
        this.socket.setSendBufferSize(16384);
        this.socket.setReceiveBufferSize(16384);
      } catch (SocketException e) {
        while (true) {
          log.debug("Unable to bind RTP port to " + port);
          this.socket = null;

          port += 2;
        }

      }

    }

    if (this.socket == null)
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Unable to bind local port in range " + this.portMin + "-" + this.portMax);
  }

  public void setSequenceNumber(int seqn)
  {
    this.seqn = seqn;
  }

  private InetAddress resolveAddress(String host) throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e1) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Unable to resolve " + host);
    }
  }

  public void close() {
    this.running = false;
    if (this.socket != null) {
      this.socket.close();
      this.socket = null;
    }
  }

  public RtpPacket createRtpPacket(int pt, int payloadSize) {
    byte[] buffer = new byte[payloadSize + 12];
    RtpPacket packet = new RtpPacket(buffer, payloadSize + 12);
    packet.init(pt, -555684209L);
    return packet;
  }

  public void sendRtpPacket(RtpPacket packet) throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    long timestamp = System.nanoTime();

    packet.setSequenceNumber(this.seqn);
    packet.setTimestamp(timestamp / 1000000L);
    sendPacket(packet.getPacket(), packet.getLength());
    this.sentTime.add(Long.valueOf(timestamp));
    this.recvTime.add(Long.valueOf(timestamp));

    this.seqn += 1;
  }

  public void sendPacket(byte[] data, int length) throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    try {
      this.socket.send(new DatagramPacket(data, length, this.remoteAddr, this.remotePort));
    } catch (PortUnreachableException e) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Sending packet failed: Port unreachable");
    } catch (IOException e) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Sending packet failed: " + e.getMessage());
    }
  }

  public DatagramPacket receivePacketTimeout() throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    DatagramPacket recv = new DatagramPacket(new byte[1500], 1500);
    try {
      this.socket.setSoTimeout(100);
      this.socket.receive(recv);
    } catch (SocketTimeoutException e) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Receiving packet timed out");
    } catch (PortUnreachableException e) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Receiving packet failed: Port unreachable");
    } catch (IOException e) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Receiving packet failed: " + e.getMessage());
    }

    if (isCorrectSenderAddress(recv)) {
      return recv;
    }

    throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Unable to receive RTP packet within timeout");
  }

  public void receivePacketLoop() {
    while (this.running) {
      DatagramPacket recv = new DatagramPacket(new byte[1500], 1500);
      try {
        this.socket.setSoTimeout(100);
        this.socket.receive(recv);
        long received = System.nanoTime();

        if (isCorrectSenderAddress(recv)) {
          RtpPacket incoming = new RtpPacket(recv.getData(), recv.getLength());
          int seqn = incoming.getSequenceNumber();
          if (seqn >= 0)
            this.recvTime.set(seqn, Long.valueOf(received));
          else
            log.debug("Skipping sequence # " + seqn + " " + incoming.getSequenceNumber());
        }
        else {
          log.debug("Skipping packet from " + recv.getAddress());
        }
      }
      catch (SocketTimeoutException e) {
        log.debug("Receiving packet timed out");
      } catch (SocketException e) {
        log.error("Socket error:" + e);
      } catch (IOException e) {
        log.error("IO Socket error:" + e);
      } catch (Throwable e) {
        log.error("General error:" + e);
      }
    }
  }

  public void checkRtpPacket(RtpPacket expected, DatagramPacket dgram) throws com.zijingcloud.testonsite.rtp.RtpSenderException {
    RtpPacket received = new RtpPacket(dgram.getData(), dgram.getLength());

    if (received.getSscr() != expected.getSscr()) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Wrong SSRC in RTP header");
    }

    if (received.getPayloadType() != expected.getPayloadType()) {
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Wrong payload type in RTP header");
    }

    if (received.getPayloadLength() != expected.getPayloadLength())
      throw new com.zijingcloud.testonsite.rtp.RtpSenderException("Wrong payload size in RTP packet");
  }

  private boolean isCorrectSenderAddress(DatagramPacket recv)
  {
    return ((recv.getAddress().equals(this.remoteAddr)) && (recv.getPort() == this.remotePort));
  }

  public void run() {
    this.running = true;
    Thread t = Thread.currentThread();
    t.setPriority(8);
    receivePacketLoop();
  }

  public void calculateStatistics(int start, int end) {
    int totalPackets = 0;
    int lostPackets = 0;
    this.success = true;

    int prevTransit = 0;
    double Ji = 0D;

    TreeSet sortedJitter = new TreeSet();
    TreeSet sortedDelay = new TreeSet();
    boolean firstPacket = true;

    for (int i = start; i < end; ++i) {
      long sentAt = ((Long)this.sentTime.get(i)).longValue();
      long recvAt = ((Long)this.recvTime.get(i)).longValue();

      ++totalPackets;

      if (sentAt >= recvAt) {
        log.debug(i + " detected packet loss! ");
        ++lostPackets;
      }
      else
      {
        int transitMS = (int)((recvAt - sentAt) / 1000000L);
        int d = transitMS - prevTransit;
        prevTransit = transitMS;
        if (d < 0) d = -d;
        if (!(firstPacket))
          Ji += 0.25D * (d - Ji);
        firstPacket = false;

        log.debug(i + " got transit time " + transitMS + "ms with jitter " + Ji);

        sortedDelay.add(Integer.valueOf(transitMS));
        sortedJitter.add(Integer.valueOf((int)Ji));
      }
    }
    double loss = lostPackets / (totalPackets + 0D) * 100.0D;

    StringBuilder diag = new StringBuilder("");
    String info = "Lost " + lostPackets + " of " + totalPackets + " (is " + loss + "%)";
    log.info(info);
    if (loss > 1D) {
      this.success = false;
      diag.append("!! - loss is greater than 1% - !!\n");
    }
    diag.append("  " + info + "\n");

    Integer[] sorted = (Integer[])sortedJitter.toArray(new Integer[0]);
    int max = sorted.length - 1;
    info = "jitter: (" + sorted[0] + " - " + sorted[(int)(max * 0.10000000000000001D)] + " - " + sorted[(int)(max * 0.5D)] + " - " + sorted[(int)(max * 0.90000000000000002D)] + " - " + sorted[max] + ")";
    log.info(info);
    if (sorted[(int)(max * 0.5D)].intValue() > 20) {
      diag.append("!! - jitter.50 > 20ms - !!\n");
      this.success = false;
    }
    if (sorted[(int)(max * 0.90000000000000002D)].intValue() > 40) {
      diag.append("!! - jitter.90 > 40ms - !!\n");
      this.success = false;
    }
    diag.append("  " + info + "\n");

    sorted = (Integer[])sortedDelay.toArray(new Integer[0]);
    max = sorted.length - 1;
    info = "delay: (" + sorted[0] + " - " + sorted[(int)(max * 0.10000000000000001D)] + " - " + sorted[(int)(max * 0.5D)] + " - " + sorted[(int)(max * 0.90000000000000002D)] + " - " + sorted[max] + ")";
    log.info(info);
    if (sorted[(int)(max * 0.5D)].intValue() > 400) {
      diag.append("!! - delay.50 > 400ms - !!\n");
      this.success = false;
    }
    if (sorted[(int)(max * 0.90000000000000002D)].intValue() > 420) {
      diag.append("!! - delay.90 > 420ms !!\n");
      this.success = false;
    }
    if (sorted[(int)(max * 0.90000000000000002D)].intValue() - sorted[(int)(max * 0.10000000000000001D)].intValue() > 150) {
      diag.append("!! - delay.90-.10 > 150ms (route change?) - !!\n");
      this.success = false;
    }
    diag.append("  " + info);

    this.diagnostics = diag.toString();
  }
}
