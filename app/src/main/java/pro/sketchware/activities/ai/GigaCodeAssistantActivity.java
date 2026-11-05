package pro.sketchware.activities.ai;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.besome.sketch.lib.base.BaseAppCompatActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import chat.giga.model.completion.ChatMessage;
import pro.sketchware.R;
import pro.sketchware.ai.GigaChatAgent;
import pro.sketchware.ai.GigaChatCredentials;
import pro.sketchware.databinding.ActivityGigacodeAssistantBinding;

public class GigaCodeAssistantActivity extends BaseAppCompatActivity {
    private static final String PRIVACY_PREFS = "gigachat_agent_privacy";
    private static final String PRIVACY_ACCEPTED = "project_context_consent";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ActivityGigacodeAssistantBinding binding;
    private String projectId;
    private GigaChatAgent agent;
    private List<ChatMessage> conversation;
    private boolean requestInProgress;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        enableEdgeToEdgeNoContrast();
        super.onCreate(savedInstanceState);
        binding = ActivityGigacodeAssistantBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        projectId = getIntent().getStringExtra("sc_id");
        if (projectId == null || projectId.isBlank()) {
            finish();
            return;
        }

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.toolbar.getMenu().add("API key").setOnMenuItemClickListener(item -> {
            showCredentialsDialog();
            return true;
        });
        binding.sendButton.setOnClickListener(view -> sendMessage());
        binding.messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        if (getSharedPreferences(PRIVACY_PREFS, MODE_PRIVATE).getBoolean(PRIVACY_ACCEPTED, false)) {
            initializeAgent();
        } else {
            showPrivacyDialog();
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        if (agent != null) agent.close();
        super.onDestroy();
    }

    private void showPrivacyDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Project context")
                .setMessage("When you use GigaCode, messages and files read by the agent are sent to the GigaChat API for processing.")
                .setNegativeButton(R.string.common_word_cancel, (dialog, which) -> finish())
                .setPositiveButton("Continue", (dialog, which) -> {
                    getSharedPreferences(PRIVACY_PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PRIVACY_ACCEPTED, true)
                            .apply();
                    initializeAgent();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void initializeAgent() {
        String credentials = GigaChatCredentials.getCredentials(this);
        if (credentials == null || credentials.isBlank()) {
            showCredentialsDialog();
            return;
        }
        try {
            if (agent != null) agent.close();
            agent = new GigaChatAgent(this, projectId, credentials, GigaChatCredentials.getScope(this));
            conversation = agent.newConversation();
            binding.messageInput.setEnabled(true);
            binding.sendButton.setEnabled(true);
            if (binding.messages.getChildCount() == 0) {
                appendMessage("GigaCode", "Ready to help with this Sketchware project. Ask me to inspect or change its Java and layout files.", false);
            }
        } catch (IOException e) {
            appendMessage("Error", e.getMessage(), false);
        }
    }

    private void showCredentialsDialog() {
        EditText credentialsInput = new EditText(this);
        credentialsInput.setHint("Base64(Client_ID:Client_Secret)");
        credentialsInput.setSingleLine(true);
        credentialsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Spinner scopeSpinner = new Spinner(this);
        String[] scopes = {"Personal", "Business", "Corporate"};
        scopeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, scopes));
        String currentScope = GigaChatCredentials.getScope(this);
        scopeSpinner.setSelection(GigaChatCredentials.BUSINESS_SCOPE.equals(currentScope) ? 1
                : GigaChatCredentials.CORPORATE_SCOPE.equals(currentScope) ? 2 : 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(24);
        content.setPadding(horizontalPadding, dp(8), horizontalPadding, 0);
        content.addView(credentialsInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(scopeSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("GigaChat API key")
                .setMessage("Enter the authorization key from Sber Developers. It is encrypted with Android Keystore.")
                .setView(content)
                .setNegativeButton(R.string.common_word_cancel, (dialog, which) -> {
                    if (GigaChatCredentials.getCredentials(this) == null) finish();
                })
                .setPositiveButton("Save", null);
        var dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String credentials = credentialsInput.getText().toString().trim();
            if (credentials.isEmpty()) {
                credentialsInput.setError("Enter the API key");
                return;
            }
            String scope = switch (scopeSpinner.getSelectedItemPosition()) {
                case 1 -> GigaChatCredentials.BUSINESS_SCOPE;
                case 2 -> GigaChatCredentials.CORPORATE_SCOPE;
                default -> GigaChatCredentials.PERSONAL_SCOPE;
            };
            try {
                GigaChatCredentials.save(this, credentials, scope);
                dialog.dismiss();
                initializeAgent();
            } catch (Exception e) {
                credentialsInput.setError("Could not encrypt the API key");
            }
        }));
        dialog.show();
    }

    private void sendMessage() {
        if (requestInProgress || agent == null) return;
        String text = binding.messageInput.getText() == null
                ? ""
                : binding.messageInput.getText().toString().trim();
                if (text.isEmpty()) {
                    return;
                }

        binding.messageInput.setText("");
        appendMessage("You", text, true);
        setRequestInProgress(true);
        executor.execute(() -> {
            try {
                String answer = agent.reply(conversation, text);
                if (agent.isBuildRequested()) {
                    runOnUiThread(() -> {
                        Intent buildRequest = new Intent();
                        buildRequest.putExtra("run_build", true);
                        setResult(RESULT_OK, buildRequest);
                        finish();
                    });
                    return;
                }
                runOnUiThread(() -> {
                    appendMessage("GigaCode", answer.isBlank() ? "Task completed." : answer, false);
                    setRequestInProgress(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendMessage("Error", e.getMessage() == null ? "Request failed." : e.getMessage(), false);
                    setRequestInProgress(false);
                });
            }
        });
    }

    private void setRequestInProgress(boolean inProgress) {
        requestInProgress = inProgress;
        binding.sendButton.setEnabled(!inProgress);
        binding.messageInput.setEnabled(!inProgress);
        binding.progress.setVisibility(inProgress ? View.VISIBLE : View.GONE);
    }

    private void appendMessage(String author, String text, boolean isUser) {
        MaterialCardView bubble = new MaterialCardView(this);
        bubble.setRadius(dp(12));
        bubble.setCardBackgroundColor(themeColor(isUser
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainer));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                isUser ? (int) (getResources().getDisplayMetrics().widthPixels * 0.82f)
                        : LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = isUser ? Gravity.END : Gravity.START;
        cardParams.bottomMargin = dp(8);

        TextView message = new TextView(this);
        message.setText(author + "\n" + text);
        message.setTextColor(themeColor(isUser
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurface));
        message.setTextIsSelectable(true);
        message.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.addView(message);
        binding.messages.addView(bubble, cardParams);

        ScrollView scrollView = binding.messageScroll;
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private int themeColor(int attribute) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attribute, value, true)) return value.data;
        return getColor(android.R.color.darker_gray);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}