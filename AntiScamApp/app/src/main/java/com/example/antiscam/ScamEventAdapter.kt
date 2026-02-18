package com.example.antiscam

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScamEventAdapter(private val events: List<ScamEvent>) :
    RecyclerView.Adapter<ScamEventAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRisk: TextView = view.findViewById(R.id.tv_item_risk)
        val tvApp: TextView = view.findViewById(R.id.tv_item_app)
        val tvSnippet: TextView = view.findViewById(R.id.tv_item_snippet)
        val tvTime: TextView = view.findViewById(R.id.tv_item_time)
        val tvAction: TextView = view.findViewById(R.id.tv_item_action)
        val riskCircle: View = view.findViewById(R.id.risk_badge_circle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scam_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]

        holder.tvRisk.text = "${event.riskScore.toInt()}%"
        holder.tvApp.text = event.appName
        holder.tvSnippet.text = event.textSnippet
        holder.tvTime.text = timeFormat.format(Date(event.timestamp))

        // Style based on action type
        when (event.action) {
            "BLOCKED" -> {
                holder.tvAction.text = "BLOCKED"
                holder.tvAction.setTextColor(0xFFEF4444.toInt())
                holder.tvAction.setBackgroundResource(R.drawable.bg_badge_blocked)
                holder.riskCircle.setBackgroundResource(R.drawable.risk_badge_bg)
            }
            "SAFE" -> {
                holder.tvAction.text = "SAFE"
                holder.tvAction.setTextColor(0xFF4ADE80.toInt())
                holder.tvAction.setBackgroundResource(R.drawable.bg_badge_safe)
                holder.riskCircle.setBackgroundResource(R.drawable.risk_badge_safe_bg)
            }
            else -> {
                holder.tvAction.text = "DISMISSED"
                holder.tvAction.setTextColor(0xFFFBBF24.toInt())
                holder.tvAction.setBackgroundResource(R.drawable.bg_badge_dismissed)
                holder.riskCircle.setBackgroundResource(R.drawable.risk_badge_bg)
            }
        }
    }

    override fun getItemCount() = events.size
}
