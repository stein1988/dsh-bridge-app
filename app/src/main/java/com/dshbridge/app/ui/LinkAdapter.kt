package com.dshbridge.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.dshbridge.app.R
import com.dshbridge.app.data.LinkRecord
import com.dshbridge.app.databinding.ItemLinkBinding
import com.dshbridge.app.util.TimeText

/**
 * 首页卡片列表。
 *
 * adapter 不碰存储与网络：连通性结果、是否已存密码都由 Activity 算好喂进来（[submit]），
 * 保持绑定阶段无副作用、也不会在滚动时触发 IO。
 */
class LinkAdapter(
    private val onClick: (LinkRecord) -> Unit,
    private val onMenu: (View, LinkRecord) -> Unit,
) : RecyclerView.Adapter<LinkAdapter.Holder>() {

    private var rows: List<LinkRecord> = emptyList()

    /** id -> true(可达) / false(不可达) / null(检测中) */
    private var reachability: Map<String, Boolean?> = emptyMap()
    private var passwordIds: Set<String> = emptySet()

    fun submit(
        items: List<LinkRecord>,
        reachability: Map<String, Boolean?>,
        passwordIds: Set<String>,
    ) {
        this.rows = items
        this.reachability = reachability
        this.passwordIds = passwordIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    inner class Holder(private val binding: ItemLinkBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: LinkRecord) {
            val context = binding.root.context

            binding.tvTitle.text = record.displayTitle

            // 「局域网 · 192.168.1.10:3082」/「远程 · xxx.trycloudflare.com」
            val kind = context.getString(
                if (record.isLocal) R.string.kind_local else R.string.kind_remote
            )
            binding.tvUrl.text = "$kind · ${record.displayUrl}"

            val reachable = reachability[record.id]
            val statusText = when (reachable) {
                true -> context.getString(R.string.status_reachable)
                false -> context.getString(R.string.status_unreachable)
                null -> context.getString(R.string.status_checking)
            }
            binding.tvStatus.text = context.getString(
                R.string.status_line,
                statusText,
                TimeText.relative(context, record.lastOpenedAt),
            )

            val dotColorRes = when (reachable) {
                true -> R.color.ok
                false -> R.color.offline
                null -> R.color.warn
            }
            binding.dotStatus.backgroundTintList =
                ContextCompat.getColorStateList(context, dotColorRes)

            binding.tvPasswordBadge.visibility =
                if (record.id in passwordIds) View.VISIBLE else View.GONE

            binding.btnMore.setOnClickListener { onMenu(it, record) }
            binding.root.setOnClickListener { onClick(record) }
        }
    }
}
