package com.example.antiscam

import android.graphics.Color
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

        // Action badge styling
        if (event.action == "BLOCKED") {
            holder.tvAction.text = "BLOCKED"
            holder.tvAction.setTextColor(Color.parseColor("#F85149"))
            holder.tvAction.setBackgroundColor(Color.parseColor("#1AF85149"))
        } else {
            holder.tvAction.text = "DISMISSED"
            holder.tvAction.setTextColor(Color.parseColor("#D29922"))
            holder.tvAction.setBackgroundColor(Color.parseColor("#1AD29922"))
        }
    }

    override fun getItemCount() = events.size
}
