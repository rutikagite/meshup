package com.example.meshup.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshup.R;
import com.example.meshup.ui.data.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_SENT = 1;
    private static final int VIEW_TYPE_RECEIVED = 2;

    private List<ChatMessage> messages;
    private String currentUserId;

    public ChatMessagesAdapter(List<ChatMessage> messages, String currentUserId) {
        this.messages = messages;
        this.currentUserId = currentUserId;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        if (message.getSenderId().equals(currentUserId)) {
            return VIEW_TYPE_SENT;
        } else {
            return VIEW_TYPE_RECEIVED;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_SENT) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_sent, parent, false);
            return new SentMessageViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message_received, parent, false);
            return new ReceivedMessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);

        if (holder instanceof SentMessageViewHolder) {
            ((SentMessageViewHolder) holder).bind(message);
        } else if (holder instanceof ReceivedMessageViewHolder) {
            ((ReceivedMessageViewHolder) holder).bind(message);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // ViewHolder for sent messages
    class SentMessageViewHolder extends RecyclerView.ViewHolder {
        private TextView textViewMessage;
        private TextView textViewTime;

        public SentMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewMessage = itemView.findViewById(R.id.textView_message);
            textViewTime = itemView.findViewById(R.id.textView_time);
        }

        public void bind(ChatMessage message) {
            textViewMessage.setText(message.getContent());
            textViewTime.setText(formatTime(message.getTimestamp()));
        }
    }

    // ViewHolder for received messages
    class ReceivedMessageViewHolder extends RecyclerView.ViewHolder {
        private TextView textViewMessage;
        private TextView textViewTime;
        private TextView textViewSenderName;

        public ReceivedMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewMessage = itemView.findViewById(R.id.textView_message);
            textViewTime = itemView.findViewById(R.id.textView_time);
            textViewSenderName = itemView.findViewById(R.id.textView_sender_name);
        }

        public void bind(ChatMessage message) {
            textViewMessage.setText(message.getContent());
            textViewTime.setText(formatTime(message.getTimestamp()));
            textViewSenderName.setText(message.getSenderName());
        }
    }

    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}