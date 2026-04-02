const mongoose = require('mongoose');

const taskSchema = new mongoose.Schema({
  deviceId: { type: String, required: false, default: 'global', index: true },
  name:     { type: String, required: true },
  steps:    { type: [mongoose.Schema.Types.Mixed], default: [] },
  createdAt: { type: Date, default: Date.now },
  updatedAt: { type: Date, default: Date.now }
});

module.exports = mongoose.model('Task', taskSchema);
