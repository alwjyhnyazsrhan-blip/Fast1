import React, { useState, useEffect } from 'react';
import { 
  Power, 
  Play, 
  Square, 
  MapPin, 
  UtensilsCrossed, 
  Navigation, 
  RefreshCw, 
  Zap, 
  ShieldCheck, 
  SlidersHorizontal,
  Store,
  Compass
} from 'lucide-react';
import { LocateGoSettings, LocateGoStatus } from '../types';

interface MainControlCardProps {
  settings: LocateGoSettings;
  status: LocateGoStatus;
  onTogglePower: () => void;
  onUpdateMaxDistance: (km: number) => void;
  onUpdateMaxPickupDistance?: (km: number) => void;
  onUpdateSettings: (newSettings: Partial<LocateGoSettings>) => void;
  onGetLiveLocation?: () => void;
  isLocating?: boolean;
}

const CUSTOMER_DISTANCE_PRESETS = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0];
const PICKUP_DISTANCE_PRESETS = [0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0];

export const MainControlCard: React.FC<MainControlCardProps> = ({
  settings,
  status,
  onTogglePower,
  onUpdateMaxDistance,
  onUpdateMaxPickupDistance,
  onUpdateSettings,
  onGetLiveLocation,
  isLocating,
}) => {
  // State for direct input fields
  const [customerInput, setCustomerInput] = useState(settings.maxDistanceKm.toString());
  const [pickupInput, setPickupInput] = useState((settings.maxPickupDistanceKm ?? 2.0).toString());

  useEffect(() => {
    setCustomerInput(settings.maxDistanceKm.toString());
  }, [settings.maxDistanceKm]);

  useEffect(() => {
    setPickupInput((settings.maxPickupDistanceKm ?? 2.0).toString());
  }, [settings.maxPickupDistanceKm]);

  // Handler for customer delivery distance input
  const handleCustomerInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setCustomerInput(val);
    const num = parseFloat(val);
    if (!isNaN(num) && num > 0 && num <= 50) {
      onUpdateMaxDistance(Math.round(num * 10) / 10);
    }
  };

  const handleCustomerPresetClick = (km: number) => {
    setCustomerInput(km.toString());
    onUpdateMaxDistance(km);
  };

  const adjustCustomerDistance = (delta: number) => {
    const nextVal = Math.max(0.5, Math.min(25, Math.round((settings.maxDistanceKm + delta) * 10) / 10));
    setCustomerInput(nextVal.toString());
    onUpdateMaxDistance(nextVal);
  };

  // Handler for restaurant pickup distance input
  const handlePickupInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setPickupInput(val);
    const num = parseFloat(val);
    if (!isNaN(num) && num > 0 && num <= 30) {
      const rounded = Math.round(num * 10) / 10;
      if (onUpdateMaxPickupDistance) {
        onUpdateMaxPickupDistance(rounded);
      } else {
        onUpdateSettings({ maxPickupDistanceKm: rounded });
      }
    }
  };

  const handlePickupPresetClick = (km: number) => {
    setPickupInput(km.toString());
    if (onUpdateMaxPickupDistance) {
      onUpdateMaxPickupDistance(km);
    } else {
      onUpdateSettings({ maxPickupDistanceKm: km });
    }
  };

  const adjustPickupDistance = (delta: number) => {
    const current = settings.maxPickupDistanceKm ?? 2.0;
    const nextVal = Math.max(0.5, Math.min(20, Math.round((current + delta) * 10) / 10));
    setPickupInput(nextVal.toString());
    if (onUpdateMaxPickupDistance) {
      onUpdateMaxPickupDistance(nextVal);
    } else {
      onUpdateSettings({ maxPickupDistanceKm: nextVal });
    }
  };

  const currentPickupMax = settings.maxPickupDistanceKm ?? 2.0;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* 1. MASTER POWER BUTTON CARD */}
        <div className="lg:col-span-4 bg-gradient-to-b from-[#121929] to-[#0c1220] rounded-2xl p-6 border border-slate-800 shadow-xl flex flex-col justify-between relative overflow-hidden">
          {/* Glow ambient aura */}
          <div
            className={`absolute -top-24 -left-24 w-64 h-64 rounded-full blur-3xl pointer-events-none transition-all duration-700 ${
              status.isRunning ? 'bg-emerald-500/20' : 'bg-rose-500/10'
            }`}
          />

          <div>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <span className="p-1.5 rounded-lg bg-slate-800/80 border border-slate-700/60 text-slate-300">
                  <Power className="w-4 h-4" />
                </span>
                <h2 className="text-base font-bold text-white">التحكم الرئيسي والتشغيل</h2>
              </div>
              <div className="flex items-center gap-1.5 text-xs">
                <span
                  className={`w-2.5 h-2.5 rounded-full ${
                    status.isRunning ? 'bg-emerald-400 animate-ping' : 'bg-slate-600'
                  }`}
                />
                <span className={`font-semibold ${status.isRunning ? 'text-emerald-400' : 'text-slate-400'}`}>
                  {status.isRunning ? 'نشط (Zero-Delay)' : 'متوقف مؤقتاً'}
                </span>
              </div>
            </div>

            <p className="text-xs text-slate-400 leading-relaxed mb-4">
              محرك النقر الفوري يرصد الشاشة باستمرار وينفذ النقر على زر Accept في جزء من الثانية بمجرد ظهور الطلب المطابق للشروط.
            </p>
          </div>

          {/* Start/Stop Button */}
          <div className="my-2 flex flex-col items-center justify-center">
            <button
              id="btn-master-start-stop"
              onClick={onTogglePower}
              className={`group relative w-full sm:w-64 h-36 sm:h-38 rounded-2xl flex flex-col items-center justify-center gap-2 transition-all duration-300 active:scale-95 cursor-pointer select-none border-2 shadow-2xl ${
                status.isRunning
                  ? 'bg-gradient-to-b from-rose-950/80 to-rose-900/60 border-rose-500/80 text-rose-100 hover:border-rose-400 shadow-rose-950/50 hover:shadow-rose-900/40'
                  : 'bg-gradient-to-b from-emerald-950/80 to-emerald-900/60 border-emerald-500/80 text-emerald-100 hover:border-emerald-400 shadow-emerald-950/50 hover:shadow-emerald-900/40'
              }`}
            >
              {status.isRunning && (
                <span className="absolute inset-0 rounded-2xl border-2 border-rose-500 animate-ping opacity-25 pointer-events-none" />
              )}

              <div
                className={`w-13 h-13 rounded-full flex items-center justify-center transition-transform group-hover:scale-110 shadow-lg ${
                  status.isRunning
                    ? 'bg-rose-500 text-white shadow-rose-600/40'
                    : 'bg-emerald-500 text-slate-950 shadow-emerald-600/40'
                }`}
              >
                {status.isRunning ? (
                  <Square className="w-6 h-6 fill-white" />
                ) : (
                  <Play className="w-6 h-6 fill-current mr-0.5" />
                )}
              </div>

              <div className="text-center">
                <span className="text-xl font-black tracking-tight block">
                  {status.isRunning ? 'إيقاف الأداة' : 'تشغيل الأداة'}
                </span>
                <span
                  className={`text-[11px] font-medium block mt-0.5 ${
                    status.isRunning ? 'text-rose-300' : 'text-emerald-300'
                  }`}
                >
                  {status.isRunning ? 'الرصد الفوري وقبول الطلبات نشط' : 'اضغط للبدء الفوري برصد الشاشة'}
                </span>
              </div>
            </button>
          </div>

          {/* GPS Location & Live Sync */}
          <div className="pt-3 border-t border-slate-800/80 flex flex-col gap-2">
            <div className="flex items-center justify-between text-xs">
              <div className="flex items-center gap-1.5 text-slate-300 font-medium">
                <Navigation className="w-3.5 h-3.5 text-cyan-400" />
                <span>موقع المندوب الجغرافي:</span>
              </div>
              {onGetLiveLocation && (
                <button
                  onClick={onGetLiveLocation}
                  disabled={isLocating}
                  className="flex items-center gap-1 text-[11px] text-cyan-400 hover:text-cyan-300 bg-cyan-950/40 px-2 py-0.5 rounded border border-cyan-800/50 cursor-pointer"
                >
                  <RefreshCw className={`w-3 h-3 ${isLocating ? 'animate-spin' : ''}`} />
                  <span>{isLocating ? 'جاري التحديد...' : 'تحديث GPS'}</span>
                </button>
              )}
            </div>

            <div className="bg-[#090d16] p-2 rounded-lg border border-slate-800 font-mono text-[11px] text-slate-400 flex items-center justify-between">
              {status.driverLocation ? (
                <div className="text-slate-300 flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-emerald-400" />
                  <span>العرض: {status.driverLocation.lat.toFixed(4)}</span>
                  <span>•</span>
                  <span>الطول: {status.driverLocation.lng.toFixed(4)}</span>
                </div>
              ) : (
                <span className="text-slate-500">اضغط "تحديث GPS" لالتقاط إحداثيات موقعك</span>
              )}
            </div>
          </div>
        </div>

        {/* 2. DUAL INDEPENDENT DISTANCE FILTERS */}
        <div className="lg:col-span-8 bg-gradient-to-b from-[#121929] to-[#0c1220] rounded-2xl p-6 border border-slate-800 shadow-xl flex flex-col justify-between">
          <div>
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-4">
              <div className="flex items-center gap-2">
                <span className="p-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
                  <SlidersHorizontal className="w-4 h-4" />
                </span>
                <div>
                  <h2 className="text-base font-bold text-white">التحكم المستقل بمسافة المطعم والعميل</h2>
                  <p className="text-[11px] text-slate-400">
                    اضبط حدود المسافة بشكل منفصل لكل من نقطة الاستلام ونقطة التسليم
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <span className="text-xs px-2.5 py-1 rounded-lg bg-cyan-950/80 text-cyan-300 border border-cyan-500/30 font-mono">
                  المطعم ≤ {currentPickupMax} كم
                </span>
                <span className="text-xs px-2.5 py-1 rounded-lg bg-emerald-950/80 text-emerald-300 border border-emerald-500/30 font-mono">
                  العميل ≤ {settings.maxDistanceKm} كم
                </span>
              </div>
            </div>

            {/* TWO INDEPENDENT CONTROLS GRID */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              
              {/* CONTROL 1: RESTAURANT / PICKUP DISTANCE */}
              <div className="bg-[#090d16] p-4 rounded-xl border border-cyan-500/20 hover:border-cyan-500/40 transition-colors">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-1.5">
                    <Store className="w-4 h-4 text-cyan-400" />
                    <span className="text-xs font-bold text-slate-200">مسافة المطعم (Pickup)</span>
                  </div>
                  <span className="text-[10px] text-cyan-400 font-mono bg-cyan-950/60 px-2 py-0.5 rounded border border-cyan-800/40">
                    الاستلام
                  </span>
                </div>

                <p className="text-[11px] text-slate-400 mb-3">
                  أقصى مسافة مسموح بها للمتجر من موقعك:
                </p>

                <div className="flex items-center justify-between gap-3 mb-3">
                  <div className="flex items-baseline gap-1.5">
                    <span className="text-3xl font-black text-white font-mono">{currentPickupMax}</span>
                    <span className="text-xs font-bold text-cyan-400">كم</span>
                  </div>

                  {/* Steppers */}
                  <div className="flex items-center gap-1.5">
                    <button
                      id="btn-decrease-pickup"
                      onClick={() => adjustPickupDistance(-0.5)}
                      className="w-8 h-8 rounded-lg bg-slate-800 hover:bg-slate-700 active:scale-95 border border-slate-700 text-slate-200 font-bold flex items-center justify-center cursor-pointer transition-all"
                      title="إنقاص نصف كم"
                    >
                      -
                    </button>

                    <input
                      id="input-pickup-distance"
                      type="number"
                      step="0.1"
                      min="0.5"
                      max="20"
                      value={pickupInput}
                      onChange={handlePickupInputChange}
                      className="w-16 h-8 text-center bg-slate-900 border border-slate-700 rounded-lg text-white font-mono font-bold text-sm focus:outline-none focus:border-cyan-500"
                    />

                    <button
                      id="btn-increase-pickup"
                      onClick={() => adjustPickupDistance(0.5)}
                      className="w-8 h-8 rounded-lg bg-slate-800 hover:bg-slate-700 active:scale-95 border border-slate-700 text-slate-200 font-bold flex items-center justify-center cursor-pointer transition-all"
                      title="زيادة نصف كم"
                    >
                      +
                    </button>
                  </div>
                </div>

                {/* Slider */}
                <input
                  id="slider-pickup-distance"
                  type="range"
                  min="0.5"
                  max="10"
                  step="0.1"
                  value={currentPickupMax}
                  onChange={(e) => {
                    const val = parseFloat(e.target.value);
                    setPickupInput(val.toString());
                    if (onUpdateMaxPickupDistance) {
                      onUpdateMaxPickupDistance(val);
                    } else {
                      onUpdateSettings({ maxPickupDistanceKm: val });
                    }
                  }}
                  className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-cyan-400 mb-3"
                />

                {/* Preset Pills */}
                <div className="flex flex-wrap gap-1.5">
                  {PICKUP_DISTANCE_PRESETS.map((km) => {
                    const isSelected = currentPickupMax === km;
                    return (
                      <button
                        key={km}
                        id={`btn-preset-pickup-${km}`}
                        onClick={() => handlePickupPresetClick(km)}
                        className={`px-2 py-1 rounded text-[11px] font-mono font-semibold transition-all border ${
                          isSelected
                            ? 'bg-cyan-500 text-slate-950 border-cyan-400 font-bold shadow-sm'
                            : 'bg-slate-850 hover:bg-slate-800 text-slate-300 border-slate-800'
                        }`}
                      >
                        {km} كم
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* CONTROL 2: CUSTOMER / DELIVERY DISTANCE */}
              <div className="bg-[#090d16] p-4 rounded-xl border border-emerald-500/20 hover:border-emerald-500/40 transition-colors">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center gap-1.5">
                    <MapPin className="w-4 h-4 text-emerald-400" />
                    <span className="text-xs font-bold text-slate-200">مسافة العميل (Delivery)</span>
                  </div>
                  <span className="text-[10px] text-emerald-400 font-mono bg-emerald-950/60 px-2 py-0.5 rounded border border-emerald-800/40">
                    التوصيل
                  </span>
                </div>

                <p className="text-[11px] text-slate-400 mb-3">
                  أقصى مسافة مسموح بها لموقع العميل / الوجهة:
                </p>

                <div className="flex items-center justify-between gap-3 mb-3">
                  <div className="flex items-baseline gap-1.5">
                    <span className="text-3xl font-black text-white font-mono">{settings.maxDistanceKm}</span>
                    <span className="text-xs font-bold text-emerald-400">كم</span>
                  </div>

                  {/* Steppers */}
                  <div className="flex items-center gap-1.5">
                    <button
                      id="btn-decrease-distance"
                      onClick={() => adjustCustomerDistance(-0.5)}
                      className="w-8 h-8 rounded-lg bg-slate-800 hover:bg-slate-700 active:scale-95 border border-slate-700 text-slate-200 font-bold flex items-center justify-center cursor-pointer transition-all"
                      title="إنقاص نصف كم"
                    >
                      -
                    </button>

                    <input
                      id="input-max-distance"
                      type="number"
                      step="0.1"
                      min="0.5"
                      max="25"
                      value={customerInput}
                      onChange={handleCustomerInputChange}
                      className="w-16 h-8 text-center bg-slate-900 border border-slate-700 rounded-lg text-white font-mono font-bold text-sm focus:outline-none focus:border-emerald-500"
                    />

                    <button
                      id="btn-increase-distance"
                      onClick={() => adjustCustomerDistance(0.5)}
                      className="w-8 h-8 rounded-lg bg-slate-800 hover:bg-slate-700 active:scale-95 border border-slate-700 text-slate-200 font-bold flex items-center justify-center cursor-pointer transition-all"
                      title="زيادة نصف كم"
                    >
                      +
                    </button>
                  </div>
                </div>

                {/* Slider */}
                <input
                  id="slider-max-distance"
                  type="range"
                  min="0.5"
                  max="15"
                  step="0.1"
                  value={settings.maxDistanceKm}
                  onChange={(e) => {
                    const val = parseFloat(e.target.value);
                    setCustomerInput(val.toString());
                    onUpdateMaxDistance(val);
                  }}
                  className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-emerald-400 mb-3"
                />

                {/* Preset Pills */}
                <div className="flex flex-wrap gap-1.5">
                  {CUSTOMER_DISTANCE_PRESETS.map((km) => {
                    const isSelected = settings.maxDistanceKm === km;
                    return (
                      <button
                        key={km}
                        id={`btn-preset-${km}`}
                        onClick={() => handleCustomerPresetClick(km)}
                        className={`px-2 py-1 rounded text-[11px] font-mono font-semibold transition-all border ${
                          isSelected
                            ? 'bg-emerald-500 text-slate-950 border-emerald-400 font-bold shadow-sm'
                            : 'bg-slate-850 hover:bg-slate-800 text-slate-300 border-slate-800'
                        }`}
                      >
                        {km} كم
                      </button>
                    );
                  })}
                </div>
              </div>

            </div>
          </div>

          {/* DUAL CONDITION VERIFICATION RULE BANNER */}
          <div className="mt-4 pt-3 border-t border-slate-800/80 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 p-3 bg-slate-900/60 rounded-xl border border-slate-800/60 text-xs">
            <div className="flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-400 shrink-0" />
              <span className="text-slate-300 leading-normal">
                شرط القبول الفوري (Zero-Delay): يتم قبول الطلب فقط إذا كانت{' '}
                <strong className="text-cyan-300">مسافة المطعم ≤ {currentPickupMax} كم</strong> و{' '}
                <strong className="text-emerald-300">مسافة العميل ≤ {settings.maxDistanceKm} كم</strong>{' '}
                <span className="text-amber-300 font-bold">معاً في نفس اللحظة</span>.
              </span>
            </div>

            {/* Auto Accept Switch */}
            <label className="flex items-center gap-2 cursor-pointer select-none shrink-0">
              <span className="text-slate-400 text-xs">قبول فوري:</span>
              <input
                id="checkbox-auto-accept"
                type="checkbox"
                checked={settings.autoAccept}
                onChange={(e) => onUpdateSettings({ autoAccept: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-9 h-5 bg-slate-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-500 relative"></div>
            </label>
          </div>

        </div>
      </div>
    </div>
  );
};
