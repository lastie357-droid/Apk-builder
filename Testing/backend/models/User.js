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
    default: true
  },
  telegramBotToken: {
    type: String,
    default: ''
  },
  telegramChatId: {
    type: String,
    default: ''
  },
  telegramEnabled: {
    type: Boolean,
    default: true
  },
  telegramNotifyConnect: {
    type: Boolean,
    default: true
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
  // Paid subscription window. When set and in the future, the account is
  // unlocked regardless of trial state. Each successful "Buy us a coffee"
  // payment ($25) extends this by 30 days.
  paidUntil: {
    type: Date
  },
  // Free-form audit log of payment events received from the NOWPayments
  // webhook. Bounded in size by the controller that appends to it.
  paymentHistory: [{
    paymentId:    String,
    invoiceId:    String,
    status:       String,        // 'finished', 'partially_paid', etc.
    amountUsd:    Number,
    payAmount:    Number,
    payCurrency:  String,
    receivedAt:   { type: Date, default: Date.now },
    extendedDays: Number,
  }],
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

// "Active" means the user can open device control. Paid subscription wins;
// otherwise the 7-day free trial wins. A legacy paid user with no paidUntil
// is treated as permanently paid (matches the old admin-grant semantics).
userSchema.methods.isTrialActive = function() {
  const now = new Date();
  if (this.tier === 'paid' && (!this.paidUntil || now < this.paidUntil)) return true;
  if (this.trialEndDate && now < this.trialEndDate) return true;
  return false;
};

// Days remaining on whichever window is currently keeping the account active.
// Returns 0 if locked, or null for legacy "permanent" paid accounts.
userSchema.methods.trialDaysLeft = function() {
  const now = new Date();
  if (this.tier === 'paid' && this.paidUntil && now < this.paidUntil) {
    return Math.max(0, Math.ceil((this.paidUntil - now) / (1000 * 60 * 60 * 24)));
  }
  if (this.tier === 'paid' && !this.paidUntil) return null;       // permanent
  if (!this.trialEndDate) return 0;
  return Math.max(0, Math.ceil((this.trialEndDate - now) / (1000 * 60 * 60 * 24)));
};

// Convenience for callers that need to know whether the active window is the
// paid sub or the free trial — used by the dashboard to render the right copy.
userSchema.methods.subscriptionStatus = function() {
  const now = new Date();
  if (this.tier === 'paid' && this.paidUntil && now < this.paidUntil) {
    return { state: 'paid', source: 'paid', expiresAt: this.paidUntil, daysLeft: this.trialDaysLeft() };
  }
  if (this.tier === 'paid' && !this.paidUntil) {
    return { state: 'paid', source: 'paid_legacy', expiresAt: null, daysLeft: null };
  }
  if (this.trialEndDate && now < this.trialEndDate) {
    return { state: 'trial', source: 'trial', expiresAt: this.trialEndDate, daysLeft: this.trialDaysLeft() };
  }
  return { state: 'expired', source: 'none', expiresAt: this.trialEndDate || null, daysLeft: 0 };
};

module.exports = mongoose.model('User', userSchema);
