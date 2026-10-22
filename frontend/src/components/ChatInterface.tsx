"use client";

import React, { useState, useRef, useEffect } from "react";
import { Send, Bot, User, Sparkles, AlertTriangle, RefreshCw, Check } from "lucide-react";
import { HoldCard } from "./HoldCard";

interface Message {
  id: string;
  sender: "user" | "ai";
  text: string;
  timestamp: string;
  hold?: any;
  conflictPivoted?: boolean;
}

export const ChatInterface: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([
    {
      id: "welcome",
      sender: "ai",
      text: "Namaste! I am your **VivahGo Autonomous Wedding Agent**.\n\nI can coordinate your budget, find and verify vendors, and atomically place **15-minute temporary holds** on venues using our Redis concurrency layer.\n\nHow can I help you plan your wedding today?",
      timestamp: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
    },
  ]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, loading]);

  const handleSend = async (userText?: string) => {
    const textToSend = userText || input;
    if (!textToSend.trim() || loading) return;

    const userMessage: Message = {
      id: `msg_${Date.now()}`,
      sender: "user",
      text: textToSend,
      timestamp: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
    };

    setMessages((prev) => [...prev, userMessage]);
    if (!userText) setInput("");
    setLoading(true);

    try {
      const resp = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: textToSend,
          weddingId: "wedding_demo_101",
          userId: "user_demo_1",
          city: "Delhi",
          targetDate: "2026-11-14",
          guestCount: 500,
        }),
      });

      const data = await resp.json();

      const aiMessage: Message = {
        id: `ai_${Date.now()}`,
        sender: "ai",
        text: data.reply || "Your wedding request has been processed.",
        timestamp: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        hold: data.holdResult,
        conflictPivoted: data.conflictEncountered,
      };

      setMessages((prev) => [...prev, aiMessage]);
    } catch (err: any) {
      setMessages((prev) => [
        ...prev,
        {
          id: `err_${Date.now()}`,
          sender: "ai",
          text: "I encountered a connectivity issue contacting the MCP agent orchestrator.",
          timestamp: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        },
      ]);
    } finally {
      setLoading(false);
    }
  };

  const handleQuickPrompt = (prompt: string) => {
    handleSend(prompt);
  };

  return (
    <div className="flex-1 flex flex-col max-w-4xl w-full mx-auto p-4 h-[calc(100vh-4rem)]">
      {/* Quick Action Prompt Chips */}
      <div className="mb-3 flex items-center gap-2 overflow-x-auto pb-1 text-xs">
        <span className="text-stone-400 font-medium whitespace-nowrap">Try asking:</span>
        <button
          onClick={() =>
            handleQuickPrompt(
              "Find me a venue in South Delhi for 500 guests under ₹20 Lakhs and lock it in."
            )
          }
          className="whitespace-nowrap px-3 py-1.5 rounded-full bg-white border border-stone-200 text-stone-700 hover:border-rose-300 hover:text-rose-700 hover:bg-rose-50/50 transition shadow-sm"
        >
          🏰 Lock in a 500-guest Delhi Venue
        </button>
        <button
          onClick={() =>
            handleQuickPrompt(
              "Simulate simultaneous booking collision on The Grand Plaza for Nov 14"
            )
          }
          className="whitespace-nowrap px-3 py-1.5 rounded-full bg-white border border-stone-200 text-stone-700 hover:border-amber-300 hover:text-amber-700 hover:bg-amber-50/50 transition shadow-sm"
        >
          ⚡ Test Redis Collision & Dynamic Pivot
        </button>
        <button
          onClick={() => handleQuickPrompt("What is our remaining budget allocation for Catering and Decor?")}
          className="whitespace-nowrap px-3 py-1.5 rounded-full bg-white border border-stone-200 text-stone-700 hover:border-rose-300 hover:text-rose-700 hover:bg-rose-50/50 transition shadow-sm"
        >
          💰 Check Category Budgets
        </button>
      </div>

      {/* Message Feed */}
      <div className="flex-1 overflow-y-auto space-y-4 pr-2">
        {messages.map((m) => (
          <div
            key={m.id}
            className={`flex items-start gap-3 ${
              m.sender === "user" ? "flex-row-reverse" : "flex-row"
            }`}
          >
            <div
              className={`w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 ${
                m.sender === "user"
                  ? "bg-stone-900 text-white"
                  : "bg-gradient-to-tr from-rose-600 to-amber-600 text-white shadow-sm"
              }`}
            >
              {m.sender === "user" ? <User className="w-4 h-4" /> : <Bot className="w-4 h-4" />}
            </div>

            <div
              className={`max-w-[80%] rounded-2xl p-4 shadow-sm text-sm ${
                m.sender === "user"
                  ? "bg-rose-600 text-white rounded-tr-none"
                  : "bg-white border border-stone-200/80 text-stone-800 rounded-tl-none"
              }`}
            >
              <div className="whitespace-pre-wrap leading-relaxed">
                {m.text}
              </div>

              {/* Inline Hold Action Card */}
              {m.hold && (
                <HoldCard hold={m.hold} conflictPivoted={m.conflictPivoted} />
              )}

              <div
                className={`text-[10px] mt-2 text-right ${
                  m.sender === "user" ? "text-rose-200" : "text-stone-400"
                }`}
              >
                {m.timestamp}
              </div>
            </div>
          </div>
        ))}

        {loading && (
          <div className="flex items-start gap-3">
            <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-rose-600 to-amber-600 text-white flex items-center justify-center animate-pulse">
              <Bot className="w-4 h-4" />
            </div>
            <div className="bg-white border border-stone-200 rounded-2xl rounded-tl-none p-4 shadow-sm text-xs text-stone-500 flex items-center space-x-2">
              <RefreshCw className="w-4 h-4 animate-spin text-rose-600" />
              <span>Orchestrating multi-agent state graph & MCP tools...</span>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Input Form */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          handleSend();
        }}
        className="mt-3 relative"
      >
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Ask your agent to lock a venue, check budgets, or add timeline milestones..."
          disabled={loading}
          className="w-full bg-white border border-stone-300 rounded-xl px-4 py-3.5 pr-12 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-rose-500/20 focus:border-rose-500 disabled:opacity-50"
        />
        <button
          type="submit"
          disabled={loading || !input.trim()}
          className="absolute right-2 top-1/2 -translate-y-1/2 p-2 rounded-lg bg-rose-600 hover:bg-rose-700 disabled:bg-stone-200 text-white transition"
        >
          <Send className="w-4 h-4" />
        </button>
      </form>
    </div>
  );
};
