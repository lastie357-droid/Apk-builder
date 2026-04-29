const mongoose = require('mongoose');

const commandSchema = new mongoose.Schema({
    id: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true },
    command: { type: String, required: true },
    data: mongoose.Schema.Types.Mixed,
    sentAt: { type: Date, default: Date.now },
    completedAt: Date,
    status: { type: String, enum: ['pending', 'success', 'failed'], default: 'pending' },
    response: mongoose.Schema.Types.Mixed,
    error: String
});

commandSchema.index({ deviceId: 1, sentAt: -1 });

module.exports = mongoose.model('Command', commandSchema);