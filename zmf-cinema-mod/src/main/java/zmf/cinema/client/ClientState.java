// STANDALONE
package zmf.cinema.client;

import zmf.cinema.net.Payloads;

import java.util.HashMap;
import java.util.Map;

/** Menu lists received from the server, by tab ("zmf:showtimes", "zmf:snacks", "zmf:admin"). */
public final class ClientState {
    private ClientState() {}
    public static final Map<String, Payloads.UiList> lists = new HashMap<>();
    public static void reset() { lists.clear(); }
}
