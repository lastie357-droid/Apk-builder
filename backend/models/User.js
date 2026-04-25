const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');

const userSchema = new mongoose.Schema({
  email: {
    type: String,
    required: true,
    unique: true,
    lowercase: true,
    trim: true
  },
  password: {
    type: String,
    required: true,
    minlength: 6
  },
  name: {
    type: String,
    required: true,
    trim: true
  },
  role: {
    type: String,
    enum: ['admin', 'user'],
    default: 'user'
  },
  accessId: {
    type: String,
    unique: true,
    sparse: true
  },
  isActive: {
    type: Boolean,
    default: true
  },
  emailVerified: {
    type: Boolean,
    default: false
  },
  verificationCode: {
    type: String
  },
  verificationCodeExpiry: {
    type: Date
  },
  licenseAccepted: {
    type: Boolean,
    default: false
  },
  licenseAcceptedAt: {
    type: Date
  },
  tier: {
    type: String,
    enum: ['free', 'paid'],
    default: 'free'
  },
  trialStartDate: {
    type: Date
  },
  trialEndDate: {
    type: Date
  },
  createdAt: {
    type: Date,
    default: Date.now
  },
  lastLogin: {
    type: Date
  }
});

function generateAccessId() {
  const prefix = 'ACC';
  const rand = crypto.randomBytes(4).toString('hex').toUpperCase();
  const ts = Date.now().toString(36).toUpperCase().slice(-4);
  return `${prefix}-${ts}-${rand}`;
}

userSchema.pre('save', async function(next) {
  if (this.isNew) {
    this.accessId = generateAccessId();
    this.trialStartDate = new Date();
    const trialEnd = new Date();
    trialEnd.setDate(trialEnd.getDate() + 7);
    this.trialEndDate = trialEnd;
  }

  if (!this.isModified('password')) return next();

  try {
    const salt = await bcrypt.genSalt(10);
    this.password = await bcrypt.hash(this.password, salt);
    next();
  } catch (error) {
    next(error);
  }
});

userSchema.methods.comparePassword = async function(candidatePassword) {
  return await bcrypt.compare(candidatePassword, this.password);
};

userSchema.methods.isTrialActive = function() {
  if (this.tier === 'paid') return true;
  if (!this.trialEndDate) return false;
  return new Date() < this.trialEndDate;
};

userSchema.methods.trialDaysLeft = function() {
  if (this.tier === 'paid') return null;
  if (!this.trialEndDate) return 0;
  const diff = this.trialEndDate - new Date();
  return Math.max(0, Math.ceil(diff / (1000 * 60 * 60 * 24)));
};

module.exports = mongoose.model('User', userSchema);
