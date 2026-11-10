package com.bat.v2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bat.v2.core.Server

class ServerAdapter(
    private val items: List<Server>,
    private val selectedId: () -> String?,
    private val pings: Map<String, Long>,
    private val onSelect: (Server) -> Unit,
    private val onEdit: (Server) -> Unit,
    private val onDelete: (Server) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val ping: TextView = v.findViewById(R.id.tvPing)
        val edit: ImageButton = v.findViewById(R.id.btnEdit)
        val del: ImageButton = v.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.name.text = s.name
        h.info.text = "${s.protocol.uppercase()} · ${s.address}:${s.port} · ${s.network}/${s.security}" +
            (if (s.pin.isNotBlank()) " · pinned" else "") + (if (s.fragment) " · frag" else "")
        val p = pings[s.id]
        val ctx = h.itemView.context
        when {
            p == null -> { h.ping.text = "- ms"; h.ping.setTextColor(ctx.getColor(R.color.grey)) }
            p > 0 -> { h.ping.text = "$p ms"; h.ping.setTextColor(ctx.getColor(R.color.ok)) }
            p == -2L -> { h.ping.text = "..."; h.ping.setTextColor(ctx.getColor(R.color.grey)) }
            else -> { h.ping.text = "fail"; h.ping.setTextColor(ctx.getColor(R.color.err)) }
        }
        h.card.setBackgroundResource(if (s.id == selectedId()) R.drawable.bg_card_selected else R.drawable.bg_card)
        h.card.setOnClickListener { onSelect(s) }
        h.edit.setOnClickListener { onEdit(s) }
        h.del.setOnClickListener { onDelete(s) }
    }
}
