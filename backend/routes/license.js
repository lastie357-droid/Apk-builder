const express = require('express');
const router = express.Router();
const User = require('../models/User');
const { authenticateToken } = require('./auth');
const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY || 'sk_test_your_key');

/**
 * LICENSE ROUTES
 * 
 * ENDPOINTS:
 * - GET /api/license/plans - Get available plans
 * - POST /api/license/purchase - Purchase license
 * - POST /api/license/activate - Activate license
 * - GET /api/license/status - Get license status
 * - POST /api/license/renew - Renew license
 */

// License plans
const LICENSE_PLANS = {
    free: {
        name: 'Free',
        price: 0,
        currency: 'USD',
        validityDays: 7,
        maxDevices: 1,
        features: [
            'Basic device monitoring',
            '1 device',
            '7 days validity',
            'Limited features'
        ]
    },
    basic: {
        name: 'Basic',
        price: 9.99,
        currency: 'USD',
        validityDays: 30,
        maxDevices: 3,
        features: [
            'Full device monitoring',
            'Up to 3 devices',
            '30 days validity',
            'All basic features',
            'Email support'
        ]
    },
    premium: {
        name: 'Premium',
        price: 29.99,
        currency: 'USD',
        validityDays: 90,
        maxDevices: 10,
        features: [
            'Advanced monitoring',
            'Up to 10 devices',
            '90 days validity',
            'All premium features',
            'Live streaming',
            'Priority support'
        ]
    },
    enterprise: {
        name: 'Enterprise',
        price: 99.99,
        currency: 'USD',
        validityDays: 365,
        maxDevices: 100,
        features: [
            'Enterprise monitoring',
            'Up to 100 devices',
            '365 days validity',
            'All features unlocked',
            'Custom APK branding',
            'Dedicated support',
            'API access'
        ]
    }
};

/**
 * @route   GET /api/license/plans
 * @desc    Get available license plans
 * @access  Public
 */
router.get('/plans', (req, res) => {
    res.json({
        success: true,
        plans: LICENSE_PLANS
    });
});

/**
 * @route   POST /api/license/purchase
 * @desc    Purchase license (with Stripe)
 * @access  Private
 */
router.post('/purchase', authenticateToken, async (req, res) => {
    try {
        const { planType, paymentMethodId } = req.body;
        
        // Validate plan
        if (!LICENSE_PLANS[planType]) {
            return res.status(400).json({
                success: false,
                error: 'Invalid plan type'
            });
        }
        
        const plan = LICENSE_PLANS[planType];
        
        // Free plan - activate directly
        if (planType === 'free') {
            const user = await User.findById(req.userId);
            
            if (!user) {
                return res.status(404).json({
                    success: false,
                    error: 'User not found'
                });
            }
            
            // Activate free license
            const licenseKey = user.activateLicense('free', plan.validityDays);
            await user.save();
            
            return res.json({
                success: true,
                message: 'Free license activated',
                license: {
                    key: licenseKey,
                    type: 'free',
                    expiresAt: user.license.expiresAt,
                    maxDevices: user.license.maxDevices
                }
            });
        }
        
        // Paid plans - process payment
        if (!paymentMethodId) {
            return res.status(400).json({
                success: false,
                error: 'Payment method required'
            });
        }
        
        // Create payment intent with Stripe
        const paymentIntent = await stripe.paymentIntents.create({
            amount: Math.round(plan.price * 100), // Convert to cents
            currency: plan.currency.toLowerCase(),
            payment_method: paymentMethodId,
            confirm: true,
            description: `${plan.name} License - ${plan.validityDays} days`
        });
        
        if (paymentIntent.status !== 'succeeded') {
            return res.status(400).json({
                success: false,
                error: 'Payment failed'
            });
        }
        
        // Payment successful - activate license
        const user = await User.findById(req.userId);
        
        if (!user) {
            return res.status(404).json({
                success: false,
                error: 'User not found'
            });
        }
        
        // Add payment record
        user.addPayment({
            transactionId: paymentIntent.id,
            amount: plan.price,
            currency: plan.currency,
            licenseType: planType,
            validityDays: plan.validityDays,
            paymentMethod: 'stripe',
            status: 'completed',
            paidAt: new Date()
        });
        
        // Activate license
        const licenseKey = user.activateLicense(planType, plan.validityDays);
        await user.save();
        
        res.json({
            success: true,
            message: 'License purchased and activated successfully',
            payment: {
                transactionId: paymentIntent.id,
                amount: plan.price,
                currency: plan.currency
            },
            license: {
                key: licenseKey,
                type: planType,
                expiresAt: user.license.expiresAt,
                maxDevices: user.license.maxDevices,
                daysRemaining: user.getLicenseDaysRemaining()
            }
        });
        
    } catch (error) {
        console.error('Purchase error:', error);
        res.status(500).json({
            success: false,
            error: 'Payment processing failed'
        });
    }
});

/**
 * @route   GET /api/license/status
 * @desc    Get license status
 * @access  Private
 */
router.get('/status', authenticateToken, async (req, res) => {
    try {
        const user = await User.findById(req.userId);
        
        if (!user) {
            return res.status(404).json({
                success: false,
                error: 'User not found'
            });
        }
        
        res.json({
            success: true,
            license: {
                type: user.license.type,
                isActive: user.isLicenseValid(),
                key: user.license.key,
                issuedAt: user.license.issuedAt,
                expiresAt: user.license.expiresAt,
                daysRemaining: user.getLicenseDaysRemaining(),
                maxDevices: user.license.maxDevices,
                currentDevices: user.devices.length
            }
        });
        
    } catch (error) {
        console.error('License status error:', error);
        res.status(500).json({
            success: false,
            error: 'Server error'
        });
    }
});

/**
 * @route   POST /api/license/renew
 * @desc    Renew license
 * @access  Private
 */
router.post('/renew', authenticateToken, async (req, res) => {
    try {
        const { planType, paymentMethodId } = req.body;
        
        // Same as purchase
        return router.post('/purchase', authenticateToken)(req, res);
        
    } catch (error) {
        console.error('Renew error:', error);
        res.status(500).json({
            success: false,
            error: 'License renewal failed'
        });
    }
});

/**
 * @route   POST /api/license/activate-free
 * @desc    Activate free trial
 * @access  Private
 */
router.post('/activate-free', authenticateToken, async (req, res) => {
    try {
        const user = await User.findById(req.userId);
        
        if (!user) {
            return res.status(404).json({
                success: false,
                error: 'User not found'
            });
        }
        
        // Check if already has active license
        if (user.isLicenseValid()) {
            return res.status(400).json({
                success: false,
                error: 'You already have an active license'
            });
        }
        
        // Activate free license
        const plan = LICENSE_PLANS.free;
        const licenseKey = user.activateLicense('free', plan.validityDays);
        await user.save();
        
        res.json({
            success: true,
            message: 'Free trial activated',
            license: {
                key: licenseKey,
                type: 'free',
                expiresAt: user.license.expiresAt,
                daysRemaining: user.getLicenseDaysRemaining(),
                maxDevices: user.license.maxDevices
            }
        });
        
    } catch (error) {
        console.error('Free activation error:', error);
        res.status(500).json({
            success: false,
            error: 'Activation failed'
        });
    }
});

module.exports = router;
