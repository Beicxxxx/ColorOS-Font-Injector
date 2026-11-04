package com.beichen.fontinjector;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** 注入 / 备份 / 导出的历史流水，只读，用来回看「上次应用的是哪个字体」。 */
public class HistoryActivity extends AppCompatActivity {

    private final List<History.Entry> entries = new ArrayList<>();
    private Adapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        tvEmpty = findViewById(R.id.tv_history_empty);
        RecyclerView recycler = findViewById(R.id.recycler_history);
        adapter = new Adapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_history);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_history);
        toolbar.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == R.id.action_clear) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("清空历史")
                        .setMessage("只清记录，不影响字体文件。")
                        .setPositiveButton("清空", (d, w) -> {
                            History.clear(this);
                            load();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
            return true;
        });

        load();
    }

    private void load() {
        entries.clear();
        entries.addAll(History.list(this));
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView action;
            final TextView ts;
            final TextView detail;

            VH(View v) {
                super(v);
                action = v.findViewById(R.id.tv_action);
                ts = v.findViewById(R.id.tv_ts);
                detail = v.findViewById(R.id.tv_detail);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            History.Entry e = entries.get(position);
            holder.action.setText(e.actionLabel());
            holder.ts.setText(e.ts);
            StringBuilder sb = new StringBuilder();
            if (!e.font.isEmpty()) sb.append(e.font);
            if (!e.upem.isEmpty()) sb.append(" · UPM ").append(e.upem);
            if (!e.note.isEmpty()) sb.append(" · ").append(e.note);
            holder.detail.setText(sb.toString());
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
