package floobits.common.protocol.json.send;

public class PermsChange extends InitialBase{
    String name = "perms";
    String action;
    int user_id;
    String[] perms;

    public PermsChange(String action, int userId, String[] perms) {
        this.action = action;
        this.user_id = userId;
        this.perms = perms;
    }
}
