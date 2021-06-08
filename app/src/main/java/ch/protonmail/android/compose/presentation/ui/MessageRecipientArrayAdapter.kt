/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */

package ch.protonmail.android.compose.presentation.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import androidx.core.view.isVisible
import ch.protonmail.android.R
import ch.protonmail.android.api.models.MessageRecipient
import ch.protonmail.android.contacts.domain.usecase.ExtractInitials
import ch.protonmail.android.databinding.LayoutRecipientDropdownItemBinding
import ch.protonmail.android.domain.entity.EmailAddress
import ch.protonmail.android.domain.entity.Name
import me.proton.core.util.kotlin.containsNoCase
import kotlin.collections.filter as kFilter

class MessageRecipientArrayAdapter(context: Context) :
    ArrayAdapter<MessageRecipient>(context, R.layout.layout_recipient_dropdown_item) {

    private var data = emptyList<MessageRecipient>()

    fun setData(recipients: List<MessageRecipient>) {
        data = recipients
        clear()
        addAll(recipients)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflateView(parent)
        val viewHolder = view.tag as ViewHolder

        getItem(position)?.let(viewHolder::bind)

        return view
    }

    private fun inflateView(parent: ViewGroup): View {
        val binding = LayoutRecipientDropdownItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return binding.root.apply {
            tag = ViewHolder(binding)
        }
    }

    override fun getFilter() = object : Filter() {

        override fun performFiltering(constraint: CharSequence?): FilterResults {
            return if (constraint.isNullOrBlank()) {
                // No filter implemented we return all the list
                data.toFilterResults()
            } else {
                data.kFilter { messageRecipient ->
                    messageRecipient.name containsNoCase constraint ||
                        messageRecipient.emailAddress containsNoCase constraint
                }.toFilterResults()
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            clear()
            if (results.count > 0) {
                @Suppress("UNCHECKED_CAST")
                addAll(results.values as List<MessageRecipient>)
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }

        private fun List<MessageRecipient>.toFilterResults(): FilterResults =
            FilterResults().apply {
                values = this@toFilterResults
                count = size
            }
    }

    private class ViewHolder(private val binding: LayoutRecipientDropdownItemBinding) {

        fun bind(recipient: MessageRecipient) {
            val isGroup = binding.root.context.getString(R.string.members) in recipient.name
            val isContact = isGroup.not()

            with(binding) {
                contactInitialsTextView.isVisible = isContact
                contactEmailTextView.isVisible = isContact
                contactGroupIconImageView.isVisible = isGroup

                binding.contactNameTextView.text = recipient.name
                if (isContact) {
                    val extractInitials = ExtractInitials()
                    val initials = extractInitials(Name(recipient.name), EmailAddress(recipient.emailAddress))
                    contactInitialsTextView.text = initials
                    contactEmailTextView.text = recipient.emailAddress
                } else if (isGroup) {
                    contactGroupIconImageView.backgroundTintList =
                        ColorStateList.valueOf(recipient.groupColor)
                    contactGroupIconImageView.imageTintList =
                        ColorStateList.valueOf(root.context.getColor(R.color.icon_inverted))
                }
            }
        }
    }
}
