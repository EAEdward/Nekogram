package tw.nekomimi.nekogram;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

/**
 * 单群模式。
 *
 * 把 GROUP 改成目标群用户名（不带 @，例如 "mygroup" 或 "mygroup_channel"），
 * 客户端登录后会自动进入该群；返回键退到桌面而不是回到会话列表。
 *
 * 若群是公开群且有别名，直接用别名即可；私有群请改用 openByUserName 能解析的
 * 公开链接别名，或把 enter() 换成 openByUserName + 邀请链接的 joinChatByInvite。
 */
public class SingleChatMode {

    /** 目标群用户名，不带 @。留空 = 启动后读 SharedPreferences 的 group。 */
    public static final String GROUP = "";

    private static final String PREFS = "nekogram_single_chat";

    private static String group(android.content.Context ctx) {
        if (!TextUtils.isEmpty(GROUP)) return GROUP;
        try {
            return android.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .getString("single_group", "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static final boolean ENABLED = true;

    /** 启动后等多久再跳（等账号/会话列表加载完） */
    public static final long DELAY_MS = 1800L;

    /** true = 返回键不退出群（直接切到桌面），false = 正常返回 */
    public static final boolean BLOCK_BACK = true;

    public static void apply(LaunchActivity activity) {
        if (!ENABLED || TextUtils.isEmpty(GROUP) || activity == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> enter(activity), DELAY_MS);
    }

    private static void enter(LaunchActivity activity) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            final int account = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            BaseFragment last = activity.getActionBarLayout().getLastFragment();
            if (last == null || last instanceof ChatActivity) {
                return;
            }
            MessagesController.getInstance(account).openByUserName(GROUP, last, 1);
        } catch (Throwable ignore) {
        }
    }

    /** 返回键：栈顶是聊天页就吞掉，切后台 */
    public static boolean interceptBack(LaunchActivity activity) {
        if (!ENABLED || !BLOCK_BACK || activity == null) {
            return false;
        }
        try {
            BaseFragment last = activity.getActionBarLayout().getLastFragment();
            return last instanceof ChatActivity;
        } catch (Throwable t) {
            return false;
        }
    }
}
