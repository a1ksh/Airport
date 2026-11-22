package kz.edu.airportcourse.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import android.widget.EditText;
import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb;

public final class LoginActivity extends BaseActivity {
    private boolean registering;
    private boolean submitPending;
    private AuthOperation authOperation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_login);
        long existingSession = sessionId();
        if (existingSession != 0 && db.account(existingSession) != null) { enterHome(existingSession); return; }
        click(R.id.login, this::submit);
        click(R.id.register, () -> form(!registering));
        form(state != null && state.getBoolean("registering", false));
        authOperation = (AuthOperation) getLastNonConfigurationInstance();
        if (authOperation != null) {
            submitting(true);
            authOperation.attach(this);
        }
    }

    private void form(boolean register) {
        if (submitPending) return;
        registering = register;
        text(R.id.login_title, register ? "Жолаушыны тіркеу" : "Аккаунтқа кіру");
        text(R.id.login, register ? "Аккаунт ашу" : "Кіру");
        text(R.id.register, register ? "Кіруге қайту" : "Жаңа жолаушы · Тіркелу");
        visible(R.id.name, register);
    }

    private void submit() {
        if (submitPending) return;
        String email = value(R.id.email);
        String password = ((EditText) findViewById(R.id.password)).getText().toString();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { toast("Email мекенжайын тексеріңіз."); return; }
        String name = value(R.id.name);
        boolean createAccount = registering;
        if (createAccount && (name.isEmpty() || password.length() < 8)) {
            toast("Атыңызды және кемінде 8 таңбалы құпиясөз жазыңыз.");
            return;
        }
        hideKeyboard();
        submitting(true);
        authOperation = new AuthOperation();
        authOperation.attach(this);
        authOperation.start(getApplicationContext(), createAccount, name, email, password);
    }

    private void submitting(boolean pending) {
        submitPending = pending;
        int[] controls = {R.id.name, R.id.email, R.id.password, R.id.login, R.id.register};
        for (int control : controls) findViewById(control).setEnabled(!pending);
        text(R.id.login, pending ? "Күтіңіз…" : registering ? "Аккаунт ашу" : "Кіру");
    }

    private void authCompleted(AuthOperation operation, long accountId, String failure) {
        if (authOperation != operation || isFinishing() || isDestroyed()) return;
        authOperation = null;
        if (accountId >= 0) enterHome(accountId);
        else {
            submitting(false);
            toast(failure);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("registering", registering);
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return authOperation;
    }

    @Override protected void onDestroy() {
        if (authOperation != null) authOperation.detach(this, !isChangingConfigurations());
        super.onDestroy();
    }

    /** Retained during rotation. The worker keeps no Activity or Activity-owned database. */
    private static final class AuthOperation {
        private final Handler main = new Handler(Looper.getMainLooper());
        private LoginActivity owner;
        private boolean completed, delivered, abandoned;
        private long result = -1;
        private String message;

        void attach(LoginActivity activity) {
            owner = activity;
            // Wait until the recreated Activity has restored its views before finishing.
            main.post(this::deliver);
        }

        void detach(LoginActivity activity, boolean leaving) {
            if (owner != activity) return;
            owner = null;
            if (leaving) abandoned = true;
        }

        void start(Context application, boolean registering, String name, String email, String password) {
            new Thread(() -> {
                long accountId = -1;
                String failure = registering ? "Бұл email тіркелген."
                    : "Email, құпиясөз немесе аккаунттың күйін тексеріңіз.";
                try (AirportDb worker = new AirportDb(application)) {
                    if (registering) accountId = worker.register(name, email, password);
                    else {
                        var account = worker.login(email, password);
                        if (account != null) accountId = account.id();
                    }
                } catch (RuntimeException error) {
                    failure = "Кіруді аяқтау мүмкін болмады. Қайта көріңіз.";
                }
                long account = accountId;
                String error = failure;
                main.post(() -> {
                    result = account;
                    message = error;
                    completed = true;
                    deliver();
                });
            }, "airport-auth").start();
        }

        private void deliver() {
            if (!completed || delivered || abandoned || owner == null
                    || owner.isFinishing() || owner.isDestroyed() || owner.isChangingConfigurations()) return;
            delivered = true;
            LoginActivity activity = owner;
            owner = null;
            activity.authCompleted(this, result, message);
        }
    }
}
