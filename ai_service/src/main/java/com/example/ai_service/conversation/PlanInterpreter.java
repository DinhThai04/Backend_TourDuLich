package com.example.ai_service.conversation;

import static com.example.ai_service.conversation.ChatModels.*;

public interface PlanInterpreter { Plan interpret(State state,String message); }
