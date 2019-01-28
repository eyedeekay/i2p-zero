package org.getmonero.i2p.zero;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.FIFOBandwidthRefiller;
import net.i2p.router.transport.TransportUtil;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;

public class RouterWrapper {

  private Router router;
  private boolean started = false;
  private Properties routerProperties;
  private TunnelControl tunnelControl;

  public RouterWrapper(Properties p) {
    this.routerProperties = p;
  }

  public boolean isStarted() {
    return started;
  }

  public void start() {

    new Thread(()-> {
      if(started) return;
      started = true;

      router = new Router(routerProperties);

      new Thread(()->{
        router.setKillVMOnEnd(false);
        router.runRouter();
      }).start();

      new Thread(()->{
        try {
          while(true) {
            if(router.isAlive()) {
              break;
            }
            else {
              Thread.sleep(1000);
              System.out.println("Waiting for I2P router to start...");
            }
          }

          tunnelControl = new TunnelControl(router, new File(new File(routerProperties.getProperty("i2p.dir.config")), "tunnel"));
          new Thread(tunnelControl).start();

        }
        catch (Exception e) {
          e.printStackTrace();
        }
      }).start();

      Runtime.getRuntime().addShutdownHook(new Thread(()->stop()));

    }).start();

  }

  public void stop() {
    if(!started) return;
    started = false;
    tunnelControl.stop();
    System.out.println("I2P router will shut down gracefully");
    router.shutdownGracefully();
  }

  long lastTriggerTimestamp = 0;
  public void debouncedUpdateBandwidthLimitKBPerSec(int n) {
    long triggerTime = new Date().getTime();
    lastTriggerTimestamp = triggerTime;
    new Thread(()->{
      try { Thread.sleep(2000); } catch(InterruptedException e) {}
      if(lastTriggerTimestamp==triggerTime) {
        // nothing happened after we were triggered, so proceed
        updateBandwidthLimitKBPerSec(n);
      }
    }).start();
  }

  public void updateBandwidthLimitKBPerSec(int n) {
    routerProperties.put("i2np.inboundKBytesPerSecond", n);
    routerProperties.put("i2np.outboundKBytesPerSecond", n);

    var changes = new HashMap<String, String>();

    final int DEF_BURST_PCT = 10;
    final int DEF_BURST_TIME = 20;

    int inboundRate = n;
    int outboundRate = n;

    {
      float rate = inboundRate / 1.024f;
      float kb = DEF_BURST_TIME * rate;
      changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
      changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
      rate -= Math.min(rate * DEF_BURST_PCT / 100, 50);
      changes.put(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
    }
    {
      float rate = outboundRate / 1.024f;
      float kb = DEF_BURST_TIME * rate;
      changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BURST_BANDWIDTH, Integer.toString(Math.round(rate)));
      changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, Integer.toString(Math.round(kb)));
      rate -= Math.min(rate * DEF_BURST_PCT / 100, 50);
      changes.put(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, Integer.toString(Math.round(rate)));
    }

    boolean saved = router.saveConfig(changes, null);
    // this has to be after the save
    router.getContext().bandwidthLimiter().reinitialize();
    if(!saved) throw new RuntimeException("Error saving the new bandwidth limit");

  }

  public double get1sRateInKBps() {
    try { return router.getContext().bandwidthLimiter().getReceiveBps()/1024d; } catch (Exception e) { return 0; }
  }
  public double get1sRateOutKBps() {
    try { return router.getContext().bandwidthLimiter().getSendBps()/1024d; } catch (Exception e) { return 0; }
  }
  public double get5mRateInKBps() {
    try {
      return router.getContext().statManager().getRate("bw.recvRate").getRate(5*60*1000).getAverageValue()/1024d;
    }
    catch (Exception e) { return 0; }
  }
  public double get5mRateOutKBps() {
    try {
      return router.getContext().statManager().getRate("bw.sendRate").getRate(5*60*1000).getAverageValue()/1024d;
    }
    catch (Exception e) { return 0; }
  }
  public double getAvgRateInKBps() {
    try {
      return router.getContext().statManager().getRate("bw.recvRate").getLifetimeAverageValue()/1024d;
    }
    catch (Exception e) { return 0; }
  }
  public double getAvgRateOutKBps() {
    try {
      return router.getContext().statManager().getRate("bw.sendRate").getLifetimeAverageValue()/1024d;
    }
    catch (Exception e) { return 0; }
  }
  public double getTotalInMB() {
    try { return router.getContext().bandwidthLimiter().getTotalAllocatedInboundBytes()/1048576d; } catch (Exception e) { return 0; }
  }
  public double getTotalOutMB() {
    try { return router.getContext().bandwidthLimiter().getTotalAllocatedOutboundBytes()/1048576d; } catch (Exception e) { return 0; }
  }

  public TunnelControl getTunnelControl() {
    return tunnelControl;
  }

  public enum NetworkState {
    HIDDEN,
    TESTING,
    FIREWALLED,
    RUNNING,
    WARN,
    ERROR,
    CLOCKSKEW,
    VMCOMM;
  }

  public static class NetworkStateMessage {
    private NetworkState state;
    private String msg;

    NetworkStateMessage(NetworkState state, String msg) {
      setMessage(state, msg);
    }

    public void setMessage(NetworkState state, String msg) {
      this.state = state;
      this.msg = msg;
    }

    public NetworkState getState() {
      return state;
    }

    public String getMessage() {
      return msg;
    }

    @Override
    public String toString() {
      return "(" + state + "; " + msg + ')';
    }
  }

  public String _t(String s) {
    return s;
  }

  final static String PROP_I2NP_NTCP_HOSTNAME = "i2np.ntcp.hostname";
  final static String PROP_I2NP_NTCP_PORT = "i2np.ntcp.port";
  final static String PROP_I2NP_NTCP_AUTO_PORT = "i2np.ntcp.autoport";
  final static String PROP_I2NP_NTCP_AUTO_IP = "i2np.ntcp.autoip";

  public NetworkStateMessage getReachability() {
    RouterContext _context = router.getContext();
    if (_context.commSystem().isDummy())
      return new NetworkStateMessage(NetworkState.VMCOMM, "VM Comm System");
    if (_context.router().getUptime() > 60*1000 && (!_context.router().gracefulShutdownInProgress()) &&
        !_context.clientManager().isAlive())
      return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Client Manager I2CP Error - check logs"));  // not a router problem but the user should know
    // Warn based on actual skew from peers, not update status, so if we successfully offset
    // the clock, we don't complain.
    //if (!_context.clock().getUpdatedSuccessfully())
    long skew = _context.commSystem().getFramedAveragePeerClockSkew(33);
    // Display the actual skew, not the offset
    if (Math.abs(skew) > 30*1000)
      return new NetworkStateMessage(NetworkState.CLOCKSKEW, _t("ERR-Clock Skew"));
    if (_context.router().isHidden())
      return new NetworkStateMessage(NetworkState.HIDDEN, _t("Hidden"));
    RouterInfo routerInfo = _context.router().getRouterInfo();
    if (routerInfo == null)
      return new NetworkStateMessage(NetworkState.TESTING, _t("Testing"));

    CommSystemFacade.Status status = _context.commSystem().getStatus();
    NetworkState state = NetworkState.RUNNING;
    switch (status) {
      case OK:
      case IPV4_OK_IPV6_UNKNOWN:
      case IPV4_OK_IPV6_FIREWALLED:
      case IPV4_UNKNOWN_IPV6_OK:
      case IPV4_DISABLED_IPV6_OK:
      case IPV4_SNAT_IPV6_OK:
        RouterAddress ra = routerInfo.getTargetAddress("NTCP");
        if (ra == null)
          return new NetworkStateMessage(NetworkState.RUNNING, _t(status.toStatusString()));
        byte[] ip = ra.getIP();
        if (ip == null)
          return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Unresolved TCP Address"));
        // TODO set IPv6 arg based on configuration?
        if (TransportUtil.isPubliclyRoutable(ip, true))
          return new NetworkStateMessage(NetworkState.RUNNING, _t(status.toStatusString()));
        return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-Private TCP Address"));

      case IPV4_SNAT_IPV6_UNKNOWN:
      case DIFFERENT:
        return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-SymmetricNAT"));

      case REJECT_UNSOLICITED:
        state = NetworkState.FIREWALLED;
      case IPV4_DISABLED_IPV6_FIREWALLED:
        if (routerInfo.getTargetAddress("NTCP") != null)
          return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled with Inbound TCP Enabled"));
        // fall through...
      case IPV4_FIREWALLED_IPV6_OK:
      case IPV4_FIREWALLED_IPV6_UNKNOWN:
        if (((FloodfillNetworkDatabaseFacade)_context.netDb()).floodfillEnabled())
          return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled and Floodfill"));
        //if (_context.router().getRouterInfo().getCapabilities().indexOf('O') >= 0)
        //    return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled and Fast"));
        return new NetworkStateMessage(state, _t(status.toStatusString()));

      case DISCONNECTED:
        return new NetworkStateMessage(NetworkState.TESTING, _t("Disconnected - check network connection"));

      case HOSED:
        return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-UDP Port In Use - Set i2np.udp.internalPort=xxxx in advanced config and restart"));

      case UNKNOWN:
        state = NetworkState.TESTING;
      case IPV4_UNKNOWN_IPV6_FIREWALLED:
      case IPV4_DISABLED_IPV6_UNKNOWN:
      default:
        ra = routerInfo.getTargetAddress("SSU");
        if (ra == null && _context.router().getUptime() > 5*60*1000) {
          if (_context.commSystem().countActivePeers() <= 0)
            return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-No Active Peers, Check Network Connection and Firewall"));
          else if (_context.getProperty(PROP_I2NP_NTCP_HOSTNAME) == null ||
              _context.getProperty(PROP_I2NP_NTCP_PORT) == null)
            return new NetworkStateMessage(NetworkState.ERROR, _t("ERR-UDP Disabled and Inbound TCP host/port not set"));
          else
            return new NetworkStateMessage(NetworkState.WARN, _t("WARN-Firewalled with UDP Disabled"));
        }
        return new NetworkStateMessage(state, _t(status.toStatusString()));
    }
  }



}