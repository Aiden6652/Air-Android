package net.kdt.pojavlaunch.ai;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.List;

/**
 * 消息列表适配器：
 * - user：右对齐蓝色气泡
 * - assistant 普通文本：左对齐灰色气泡（流式中显示光标 ▌）
 * - assistant 工具调用 / tool 结果：左侧工具卡片（🔧 名称 + 详情，✅/❌ 状态）
 */
public class AIMessageAdapter extends RecyclerView.Adapter<AIMessageAdapter.ViewHolder> {

    private List<AiMessage> mMessages;

    public void setMessages(List<AiMessage> messages) {
        mMessages = messages;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ai_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AiMessage message = mMessages.get(position);
        if (message == null) return;

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) holder.container.getLayoutParams();

        boolean isUser = "user".equals(message.role);
        if (isUser) {
            params.gravity = Gravity.END;
        } else {
            params.gravity = Gravity.START;
        }
        holder.container.setLayoutParams(params);

        // 用户/助手气泡样式切换
        holder.bubble.setBackgroundResource(isUser ? R.drawable.ai_bubble_user : R.drawable.ai_bubble_assistant);

        // 工具卡片 or 文本气泡
        boolean showToolCard = (message.isToolCall && "assistant".equals(message.role)) || "tool".equals(message.role);
        holder.toolCard.setVisibility(showToolCard ? View.VISIBLE : View.GONE);
        holder.bubble.setVisibility(showToolCard ? View.GONE : View.VISIBLE);

        if (showToolCard) {
            String icon;
            if (message.isToolCall) {
                icon = "🔧";
            } else {
                icon = message.toolSucceeded ? "✅" : "❌";
            }
            holder.toolTitle.setText(icon + " " + (message.toolName == null ? "" : message.toolName));

            String detail;
            if (message.isToolCall) {
                // 显示参数摘要
                String args = message.toolArguments == null ? "" : message.toolArguments;
                detail = args.isEmpty() ? "调用中…" : args;
            } else {
                detail = message.content == null ? "" : message.content;
            }
            if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
            holder.toolDetail.setText(detail);
            holder.toolDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            String text = message.content == null ? "" : message.content;
            if (message.streaming) text += " ▌";
            holder.bubble.setText(text.isEmpty() && message.streaming ? "▌" : text);
        }
    }

    @Override
    public int getItemCount() {
        return mMessages == null ? 0 : mMessages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final LinearLayout container;
        final TextView bubble;
        final LinearLayout toolCard;
        final TextView toolTitle;
        final TextView toolDetail;

        ViewHolder(View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.ai_msg_container);
            bubble = itemView.findViewById(R.id.ai_msg_bubble);
            toolCard = itemView.findViewById(R.id.ai_tool_card);
            toolTitle = itemView.findViewById(R.id.ai_tool_title);
            toolDetail = itemView.findViewById(R.id.ai_tool_detail);
        }
    }
}
