package net.kdt.pojavlaunch.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.ai.AiAgent;
import net.kdt.pojavlaunch.ai.AIMessageAdapter;
import net.kdt.pojavlaunch.ai.AiProvider;
import net.kdt.pojavlaunch.ai.AiSafetyManager;
import net.kdt.pojavlaunch.ai.AiSafetyMode;
import net.kdt.pojavlaunch.ai.AiSession;
import net.kdt.pojavlaunch.ai.AiSessionStore;
import net.kdt.pojavlaunch.ai.AiSettings;

/**
 * AI 助手页（对应 iOS AIViewController + AIInputBarView + AIProviderConfig 精简版）。
 * - 消息列表（流式刷新节流 200ms）
 * - 底部输入栏（模型标签点击进入提供商配置）
 * - 安全模式切换（Safe/Ask/YOLO）
 * - 新会话
 */
public class AIFragment extends Fragment {
    public static final String TAG = "AIFragment";

    /** 流式 UI 刷新节流阈值 */
    private static final long UI_THROTTLE_MS = 200;

    private AiSession mSession;
    private AIMessageAdapter mAdapter;
    private RecyclerView mMessageList;
    private EditText mInputField;
    private ImageButton mSendButton;
    private TextView mModelLabel;
    private TextView mSafetyButton;
    private TextView mTitleView;
    private View mEmptyState;
    private TextView mEmptyTitle;
    private TextView mEmptySubtitle;
    private TextView mEmptyConfigure;

    private long mLastStreamUpdate = 0;
    private boolean mPendingRefresh = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 会话加载/创建在后台完成（IO）
        PojavApplication.sExecutorService.execute(() -> {
            AiSession session = AiSessionStore.getInstance().lastActiveSession();
            if (session == null) session = AiSessionStore.getInstance().newSession();
            AiSession finalSession = session;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (getActivity() == null) return;
                mSession = finalSession;
                if (mAdapter != null) {
                    mAdapter.setMessages(mSession.messages);
                    updateEmptyState();
                    updateTitle();
                    scrollToBottom(false);
                }
                // mAdapter 为 null 说明 onViewCreated 还没跑：它内部会读取 mSession 自行刷新
            });
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ai, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mMessageList = view.findViewById(R.id.ai_message_list);
        mAdapter = new AIMessageAdapter();
        mMessageList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mMessageList.setAdapter(mAdapter);
        if (mSession != null) mAdapter.setMessages(mSession.messages);

        mInputField = view.findViewById(R.id.ai_input_field);
        mSendButton = view.findViewById(R.id.ai_send_button);
        mModelLabel = view.findViewById(R.id.ai_model_label);
        mSafetyButton = view.findViewById(R.id.ai_safety_button);
        mTitleView = view.findViewById(R.id.ai_title);
        mEmptyState = view.findViewById(R.id.ai_empty_state);
        mEmptyTitle = view.findViewById(R.id.ai_empty_title);
        mEmptySubtitle = view.findViewById(R.id.ai_empty_subtitle);
        mEmptyConfigure = view.findViewById(R.id.ai_empty_configure);

        // 发送：点击按钮或键盘发送键
        mSendButton.setOnClickListener(v -> handleSendTap());
        mInputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                handleSendTap();
                return true;
            }
            return false;
        });

        // 模型标签 → 提供商配置
        mModelLabel.setOnClickListener(v -> showProviderConfigDialog());
        mEmptyConfigure.setOnClickListener(v -> showProviderConfigDialog());

        // 安全模式切换
        mSafetyButton.setOnClickListener(v -> showSafetyModeDialog());
        updateSafetyButton();

        // 新会话
        view.findViewById(R.id.ai_new_session_button).setOnClickListener(v -> {
            if (AiAgent.getInstance().isRunning()) {
                Toast.makeText(requireContext(), R.string.ai_ongoing, Toast.LENGTH_SHORT).show();
                return;
            }
            PojavApplication.sExecutorService.execute(() -> {
                AiSession session = AiSessionStore.getInstance().newSession();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (getActivity() == null) return;
                    mSession = session;
                    mAdapter.setMessages(mSession.messages);
                    updateEmptyState();
                    updateTitle();
                });
            });
        });

        updateModelLabel();
        updateEmptyState();
    }

    @Override
    public void onResume() {
        super.onResume();
        AiSafetyManager.getInstance().attach(requireActivity());
        updateModelLabel();
        updateSafetyButton();
    }

    @Override
    public void onPause() {
        super.onPause();
        AiSafetyManager.getInstance().detach();
    }

    // ===== 发送 / 停止 =====

    private void handleSendTap() {
        if (AiAgent.getInstance().isRunning()) {
            handleStop();
            return;
        }
        String text = mInputField.getText().toString().trim();
        if (text.isEmpty()) return;
        if (mSession == null) return;

        AiProvider provider = AiSettings.getProvider();
        if (!provider.isConfigured()) {
            showProviderConfigDialog();
            return;
        }

        mInputField.setText("");
        setSending(true);

        AiAgent.getInstance().sendUserMessage(text, mSession, provider,
                new AiAgent.AgentListener() {
                    @Override
                    public void onChunk(String delta) {
                        throttledRefresh();
                    }

                    @Override
                    public void onMessagesChanged() {
                        throttledRefresh();
                    }
                },
                error -> {
                    setSending(false);
                    mLastStreamUpdate = 0;
                    if (error != null) {
                        showError(error);
                    }
                    refreshUi();
                });

        // 用户消息已同步追加：立即刷新
        refreshUi();
    }

    private void handleStop() {
        AiAgent.getInstance().stopCurrent();
        setSending(false);
        refreshUi();
    }

    private void setSending(boolean sending) {
        mSendButton.setImageResource(sending ? R.drawable.ai_stop : R.drawable.ai_send_arrow);
    }

    /** 流式刷新节流（Agent 线程回调） */
    private void throttledRefresh() {
        long now = System.currentTimeMillis();
        if (now - mLastStreamUpdate < UI_THROTTLE_MS) {
            mPendingRefresh = true;
            return;
        }
        mLastStreamUpdate = now;
        postRefresh();
    }

    private void postRefresh() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(this::refreshUi);
    }

    private void refreshUi() {
        mPendingRefresh = false;
        if (mSession == null) return;
        mAdapter.setMessages(mSession.messages);
        updateEmptyState();
        scrollToBottom(true);
    }

    private void scrollToBottom(boolean animated) {
        if (mAdapter.getItemCount() == 0) return;
        int last = mAdapter.getItemCount() - 1;
        if (animated) mMessageList.smoothScrollToPosition(last);
        else mMessageList.scrollToPosition(last);
    }

    private void showError(String message) {
        if (getActivity() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_request_failed)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ===== 空态 =====

    private void updateEmptyState() {
        if (mEmptyState == null) return;
        AiProvider provider = AiSettings.getProvider();
        boolean hasMessages = mSession != null && !mSession.messages.isEmpty();

        if (!provider.isConfigured()) {
            mEmptyState.setVisibility(View.VISIBLE);
            mEmptyTitle.setText(R.string.ai_no_provider_title);
            mEmptySubtitle.setText(R.string.ai_no_provider_subtitle);
            mEmptyConfigure.setVisibility(View.VISIBLE);
        } else if (!hasMessages) {
            mEmptyState.setVisibility(View.VISIBLE);
            mEmptyTitle.setText(R.string.ai_empty_title);
            mEmptySubtitle.setText(R.string.ai_empty_subtitle);
            mEmptyConfigure.setVisibility(View.GONE);
        } else {
            mEmptyState.setVisibility(View.GONE);
        }
    }

    private void updateModelLabel() {
        if (mModelLabel == null) return;
        AiProvider provider = AiSettings.getProvider();
        if (provider.isConfigured()) {
            String name = provider.name.isEmpty() ? "自定义" : provider.name;
            mModelLabel.setText(name + " / " + provider.model);
        } else {
            mModelLabel.setText(R.string.ai_no_provider);
        }
    }

    private void updateTitle() {
        if (mTitleView != null && mSession != null && mSession.title != null && !mSession.title.isEmpty()) {
            mTitleView.setText(mSession.title);
        }
    }

    private void updateSafetyButton() {
        if (mSafetyButton == null) return;
        AiSafetyMode mode = AiSettings.getSafetyMode();
        mSafetyButton.setText(getString(R.string.ai_safety_fmt, mode.chineseName()));
    }

    // ===== 对话框 =====

    /** 提供商配置对话框（对应 iOS AIProviderConfigViewController 精简版） */
    private void showProviderConfigDialog() {
        AiProvider provider = AiSettings.getProvider();

        LinearLayoutLite layout = new LinearLayoutLite(requireContext());
        final EditText nameInput = layout.addField("名称（如 OpenAI / DeepSeek）", provider.name, false);
        final EditText urlInput = layout.addField("API 地址（如 https://api.openai.com/v1）", provider.baseURL, false);
        final EditText keyInput = layout.addField("API Key", provider.apiKey, true);
        final EditText modelInput = layout.addField("模型（如 gpt-4o / deepseek-chat）", provider.model, false);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_provider_config)
                .setView(layout)
                .setPositiveButton(R.string.ai_save, (d, w) -> {
                    provider.name = nameInput.getText().toString().trim();
                    provider.baseURL = urlInput.getText().toString().trim();
                    provider.apiKey = keyInput.getText().toString().trim();
                    provider.model = modelInput.getText().toString().trim();
                    AiSettings.setProvider(provider);
                    updateModelLabel();
                    updateEmptyState();
                    Toast.makeText(requireContext(), R.string.ai_saved, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.ai_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // 测试连接：用当前输入框内容（不保存、不关框）
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            AiProvider testProvider = new AiProvider();
            testProvider.name = nameInput.getText().toString().trim();
            testProvider.baseURL = urlInput.getText().toString().trim();
            testProvider.apiKey = keyInput.getText().toString().trim();
            testProvider.model = modelInput.getText().toString().trim();
            if (!testProvider.isConfigured()) {
                Toast.makeText(requireContext(), R.string.ai_config_incomplete, Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(requireContext(), R.string.ai_testing, Toast.LENGTH_SHORT).show();
            PojavApplication.sExecutorService.execute(() -> {
                String error = new net.kdt.pojavlaunch.ai.AiAPIClient().testConnection(testProvider);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (getActivity() == null) return;
                    Toast.makeText(requireContext(),
                            error == null ? getString(R.string.ai_test_ok) : error,
                            Toast.LENGTH_LONG).show();
                });
            });
        });
    }

    /** 安全模式对话框 */
    private void showSafetyModeDialog() {
        AiSafetyMode current = AiSettings.getSafetyMode();
        String[] labels = {
                "安全（推荐）——写入/联网前一律确认",
                "询问——仅危险写入确认",
                "完全（YOLO）——全部自动执行，不再确认"
        };
        final int[] checked = {current.ordinal()};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_safety_mode)
                .setSingleChoiceItems(labels, current.ordinal(), (d, which) -> checked[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    AiSettings.setSafetyMode(AiSafetyMode.fromInt(checked[0]));
                    updateSafetyButton();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 轻量垂直布局：带提示文本的输入行 */
    private static class LinearLayoutLite extends android.widget.LinearLayout {
        LinearLayoutLite(android.content.Context context) {
            super(context);
            setOrientation(VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            setPadding(pad, pad / 2, pad, pad / 2);
        }

        EditText addField(String hint, String value, boolean password) {
            TextView label = new TextView(getContext());
            label.setText(hint);
            label.setTextSize(12);
            label.setTextColor(0xFFB2B2B2);
            addView(label);
            EditText input = new EditText(getContext());
            input.setText(value);
            input.setTextSize(14);
            input.setSingleLine(true);
            if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            addView(input);
            return input;
        }
    }
}
