package com.dshbridge.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dshbridge.app.data.LinkRecord
import com.dshbridge.app.databinding.ItemLinkBinding

/**
 * 首页链接列表。
 *
 * adapter 不直接访问加密存储：由 Activity 算出"哪些链接已保存密码"后喂进来（[submit]），
 * 保持数据流向单一、也避免在绑定时做磁盘/Keystore 读取。
 */
class LinkAdapter(
    private val onClick: (LinkRecord) -> Unit,
    private val onMenu: (View, LinkRecord) -> Unit
) : RecyclerView.Adapter<LinkAdapter.Holder>() {

    private var items: List<LinkRecord> = emptyList()
    private var passwordIds: Set<String> = emptySet()

    fun submit(newItems: List<LinkRecord>, withPassword: Set<String>) {
        items = newItems
        passwordIds = withPassword
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemLinkBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: LinkRecord) {
            binding.tvTitle.text = record.displayTitle
            binding.tvUrl.text = record.displayUrl

            val hasPassword = record.id in passwordIds
            binding.tvPasswordBadge.visibility = if (hasPassword) View.VISIBLE else View.GONE

            binding.btnMore.setOnClickListener { onMenu(it, record) }
            binding.root.setOnClickListener { onClick(record) }
        }
    }
}
