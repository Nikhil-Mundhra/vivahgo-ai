"use client";

import React, { useState, useEffect } from "react";
import { Clock, ShieldCheck, CheckCircle2, XCircle, AlertCircle, Sparkles } from "lucide-react";

interface HoldCardProps {
  hold: {
    reservationId?: string;
    lockKey?: string;
    vendorName?: string;
    eventDate?: string;
    ttlSeconds?: number;
    expiresAt?: string;
  };
  conflictPivoted?: boolean;
  onApprove?: (reservationId: string) => void;
  onReject?: (reservationId: string) => void;
}

export const HoldCard: React.FC<HoldCardProps> = ({
  hold,
  conflictPivoted = false,
  onApprove,
  onReject,
}) => {
  const [secondsRemaining, setSecondsRemaining] = useState<number>(hold.ttlSeconds || 900);
  const [decision, setDecision] = useState<"pending" | "approved" | "rejected">("pending");

  useEffect(() => {
    if (secondsRemaining <= 0 || decision !== "pending") return;
    const interval = setInterval(() => {
      setSecondsRemaining((prev) => Math.max(0, prev - 1));
    }, 1000);
    return () => clearInterval(interval);
  }, [secondsRemaining, decision]);

  const formatTimer = (secs: number) => {
    const mins = Math.floor(secs / 60);
    const remainder = secs % 60;
    return `${mins}:${remainder < 10 ? "0" : ""}${remainder}`;
  };

  const handleApprove = () => {
    setDecision("approved");
    if (onApprove && hold.reservationId) {
      onApprove(hold.reservationId);
    }
  };

  const handleReject = () => {
    setDecision("rejected");
    if (onReject && hold.reservationId) {
      onReject(hold.reservationId);
    }
  };

  return (
    <div className="my-4 rounded-xl border border-stone-200 bg-white p-5 shadow-sm hover:shadow-md transition">
      {conflictPivoted && (
        <div className="mb-3 flex items-center space-x-2 rounded-lg bg-amber-50 px-3 py-2 text-xs font-medium text-amber-800 border border-amber-200">
          <AlertCircle className="w-4 h-4 text-amber-600 flex-shrink-0" />
          <span>
            Primary venue had an active hold. The agent pivoted and secured this matching slot for you!
          </span>
        </div>
      )}

      <div className="flex items-start justify-between">
        <div>
          <span className="inline-flex items-center text-xs font-semibold px-2 py-0.5 rounded bg-rose-100 text-rose-800">
            <Sparkles className="w-3 h-3 mr-1" />
            Temporary Hold Secured
          </span>
          <h4 className="text-lg font-bold text-stone-900 mt-1">
            {hold.vendorName || "Wedding Venue"}
          </h4>
          <p className="text-xs text-stone-500">Event Date: {hold.eventDate || "2026-11-14"}</p>
        </div>

        <div className="flex flex-col items-end">
          <div
            className={`flex items-center text-xs font-bold px-2.5 py-1 rounded-full ${
              secondsRemaining < 180
                ? "bg-red-100 text-red-700 animate-pulse"
                : "bg-amber-100 text-amber-800"
            }`}
          >
            <Clock className="w-3.5 h-3.5 mr-1" />
            {formatTimer(secondsRemaining)} left
          </div>
          <span className="text-[10px] text-stone-400 mt-0.5">Redis Lease Hold</span>
        </div>
      </div>

      <div className="mt-4 pt-3 border-t border-stone-100 flex items-center justify-between text-xs text-stone-600">
        <span className="flex items-center">
          <ShieldCheck className="w-4 h-4 text-emerald-600 mr-1" />
          Held exclusively for your plan
        </span>
        <span className="font-mono text-[11px] text-stone-400">
          {hold.lockKey || "lock:vendor:slot"}
        </span>
      </div>

      {decision === "pending" ? (
        <div className="mt-4 flex space-x-3">
          <button
            onClick={handleApprove}
            className="flex-1 bg-rose-600 hover:bg-rose-700 text-white font-medium py-2 px-4 rounded-lg text-xs flex items-center justify-center space-x-1.5 shadow-sm transition"
          >
            <CheckCircle2 className="w-4 h-4" />
            <span>Approve & Lock In</span>
          </button>
          <button
            onClick={handleReject}
            className="bg-stone-100 hover:bg-stone-200 text-stone-700 font-medium py-2 px-4 rounded-lg text-xs flex items-center justify-center space-x-1 transition"
          >
            <XCircle className="w-4 h-4" />
            <span>Release Hold</span>
          </button>
        </div>
      ) : decision === "approved" ? (
        <div className="mt-4 rounded-lg bg-emerald-50 border border-emerald-200 p-2.5 text-center text-xs font-semibold text-emerald-800 flex items-center justify-center space-x-1.5">
          <CheckCircle2 className="w-4 h-4 text-emerald-600" />
          <span>Hold Confirmed! Committed to PostgreSQL ACID Vault.</span>
        </div>
      ) : (
        <div className="mt-4 rounded-lg bg-stone-100 p-2.5 text-center text-xs font-medium text-stone-600">
          Hold released. Slot is open for other planners.
        </div>
      )}
    </div>
  );
};
