package com.example.ai_service.conversation;

import com.example.ai_service.advisor.AdvisorOrchestrator;
import com.example.ai_service.advisor.GroundedResponseValidator;
import com.example.ai_service.catalog.CatalogReader;
import com.example.ai_service.guard.TurnExecutor;
import com.example.ai_service.guard.TurnTrace;
import com.example.ai_service.model.ModelFailure;
import com.example.ai_service.security.AiInputPolicy;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import static com.example.ai_service.conversation.ChatModels.*;

@Service
public class ConversationService {
    private static final Logger LOG=LoggerFactory.getLogger(ConversationService.class);
    private final TurnExecutor execution;
    private final ConversationStore store;
    private final PlanInterpreter interpreter;
    private final CriteriaMerger merger;
    private final CatalogReader catalog;
    private final Validator validator;
    private final Clock clock;
    private final AdvisorOrchestrator advisor;
    private final GroundedResponseValidator responses;
    public ConversationService(ConversationStore store,PlanInterpreter interpreter,CriteriaMerger merger,
                               CatalogReader catalog,Validator validator,Clock clock,AdvisorOrchestrator advisor,GroundedResponseValidator responses) {
        this(store,interpreter,merger,catalog,validator,clock,advisor,responses,null);
    }
    @Autowired
    public ConversationService(ConversationStore store,PlanInterpreter interpreter,CriteriaMerger merger,
                               CatalogReader catalog,Validator validator,Clock clock,AdvisorOrchestrator advisor,GroundedResponseValidator responses,TurnExecutor execution) {
        this.store=store; this.interpreter=interpreter; this.merger=merger; this.catalog=catalog; this.validator=validator; this.clock=clock;
        this.advisor=advisor; this.responses=responses;
        this.execution=execution;
    }
    public Created create() { return store.create(); }
    public Created create(String owner) { return store.create(owner); }
    public Created reset(String token) { return store.reset(token); }
    public ChatResult chat(ChatRequest request) {
        var trace=new TurnTrace();
        long started=System.nanoTime();
        int status=200;
        String outcome="OK";
        try(var scope=TurnTrace.bind(trace)) {
            AiInputPolicy.requireMessage(request.message());
            String message=request.message();
            return store.turn(request.conversationId(),request.requestId(),message,
                    state->answerTurn(state,message));
        } catch(ModelFailure failure) {
            status=failure.httpStatus();
            outcome=failure.type().name();
            throw failure;
        } catch(ChatFailure failure) {
            status=failure.status();
            outcome=failure.type();
            throw failure;
        } catch(Exception failure) {
            status=500;
            outcome="AI_INTERNAL_ERROR";
            LOG.error("ai_turn_unexpected",failure);
            throw new ChatFailure(500,outcome);
        } finally {
            String id=request.requestId()!=null && request.requestId().matches(REQUEST_ID_PATTERN)
                    ? request.requestId() : "invalid";
            LOG.info("ai_turn request_id={} status={} outcome={} elapsed_ms={} model={} model_calls={} http_attempts={} prompt_tokens={} completion_tokens={} total_tokens={}",
                    id,status,outcome,(System.nanoTime()-started)/1000000,trace.model,trace.modelCalls.get(),trace.httpCalls.get(),
                    trace.promptTokens,trace.completionTokens,trace.totalTokens);
        }
    }
    private Draft answerTurn(State state,String message) {
        String stripped=message.strip();
        return execution==null
                ? responses.answer(process(state,stripped))
                : execution.execute(()->responses.answer(process(state,stripped)));
    }
    private Draft process(State state,String message) {
        Plan plan=interpreter.interpret(state,message);
        if(plan==null || !validator.validate(plan).isEmpty()) throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
        var merged=merger.merge(state.criteria(),plan,catalog::locations,LocalDate.now(clock));
        var criteria=merged.criteria();
        if(merged.hint()!=Hint.NONE) return clarify(state,criteria,merged.hint(),merged.changed(),plan.reset());
        if(plan.intent()==Intent.CLARIFY) return clarify(state,criteria,Hint.AMBIGUOUS_REQUEST,merged.changed(),plan.reset());
        if(plan.intent()==Intent.DETAIL_TOUR || plan.intent()==Intent.COMPARE_TOURS) {
            return advisor.selection(state,plan);
        }
        if(plan.intent()==Intent.POLICY) return advisor.policy(state,message);
        if(plan.intent()==Intent.OUT_OF_SCOPE)
            return answerOutside(state,"Tôi có thể giúp bạn tìm tour theo điểm đến, ngân sách và ngày đi. Với tài khoản, đặt chỗ hoặc chính sách cụ thể, bạn vui lòng xem thông tin trên website hoặc liên hệ SGU Tour.");
        int page=0;
        if(plan.intent()==Intent.MORE_TOURS) {
            if(state.latestPresentedTourIds().isEmpty()) return clarify(state,criteria,Hint.TOUR_SELECTION_REQUIRED,false,false);
            if(!state.hasMore()) return clarify(state,criteria,Hint.NO_MORE_RESULTS,false,false);
            page=state.page()+1;
        }
        // Every search, including a changed budget, reads phase B again. Never filter cached top K.
        var result=responses.search(criteria,catalog.search(criteria,page));
        String reply;
        if(result.tours().isEmpty()) reply=result.searchSummary().matchingCatalogCount()>0
                ?"Có tour trong danh mục, nhưng chưa có lịch và số chỗ phù hợp với điều kiện bạn chọn. Bạn có thể đổi ngày hoặc chủ động yêu cầu xem danh mục."
                :"Chưa tìm thấy tour khớp các điều kiện bạn chọn. Bạn có thể điều chỉnh điểm đến hoặc ngân sách; tôi sẽ giữ nguyên các điều kiện còn lại.";
        else reply="Tìm thấy "+result.tours().size()+" tour trong lượt này. Giá và số chỗ là thông tin tham khảo tại thời điểm kiểm tra, chưa phải xác nhận giữ chỗ.";
        if(criteria.maxBudgetVnd()!=null) reply+=" Ngân sách tối đa đang dùng: "+String.format(Locale.forLanguageTag("vi-VN"),"%,d",criteria.maxBudgetVnd())
                +" VND"+(criteria.budgetScope()==BudgetScope.TOTAL_GROUP?" cho cả nhóm.":" cho một người lớn.");
        return new Draft(criteria,Hint.NONE,result.tours().stream().map(TourCard::tourId).toList(),page,result.searchSummary().hasMore(),
                result.tours().isEmpty()?"NO_RESULTS":"RESULTS",reply,result.tours(),null,result.searchSummary(),plan.reset());
    }
    private Draft answerOutside(State state,String reply) {
        return new Draft(state.criteria(),state.pending(),state.latestPresentedTourIds(),state.page(),state.hasMore(),"OUT_OF_SCOPE",reply,List.of(),null,null,false);
    }
    private Draft clarify(State state,Criteria criteria,Hint hint,boolean changed,boolean reset) {
        var clarification=clarification(hint);
        return new Draft(criteria,hint,changed?List.of():state.latestPresentedTourIds(),changed?0:state.page(),changed?false:state.hasMore(),
                "CLARIFICATION",clarification.question(),List.of(),clarification,null,reset);
    }
    private Clarification clarification(Hint hint) {
        return switch(hint) {
            case BUDGET_SCOPE_REQUIRED -> new Clarification(hint.name(),List.of("budgetScope"),"Ngân sách bạn vừa nêu dành cho một người lớn hay cả nhóm?",List.of("Cho một người lớn","Cho cả nhóm"));
            case BUDGET_UNIT_REQUIRED -> new Clarification(hint.name(),List.of("maxBudgetVnd","budgetScope"),"Bạn cho biết ngân sách tính bằng đồng, nghìn hay triệu đồng và dành cho một người lớn hay cả nhóm nhé.",List.of());
            case PASSENGER_DETAILS_REQUIRED -> new Clarification(hint.name(),List.of("party"),"Để tính giá cả nhóm, bạn cho biết tổng số người lớn và trẻ em theo các nhóm tuổi: dưới 2, từ 2–4 và từ 5–11 nhé.",List.of());
            case DATE_REQUIRED -> new Clarification(hint.name(),List.of("departureFrom","departureTo"),"Bạn muốn khởi hành vào ngày, tháng và năm nào? Vui lòng chọn thời gian từ hiện tại trở đi.",List.of());
            case UNKNOWN_DESTINATION -> new Clarification(hint.name(),List.of("destinationId"),"Tôi chưa xác định được điểm đến đó trong danh mục hiện có. Bạn muốn chọn địa điểm cụ thể khác hay tìm theo miền Bắc, Trung hoặc Nam?",List.of("Miền Bắc","Miền Trung","Miền Nam"));
            case UNKNOWN_ORIGIN -> new Clarification(hint.name(),List.of("originId"),"Tôi chưa xác định được nơi khởi hành đó trong danh mục. Bạn cho biết lại nơi xuất phát nhé.",List.of());
            case TOUR_SELECTION_REQUIRED -> new Clarification(hint.name(),List.of("tourSelection"),"Bạn muốn chọn tour thứ mấy trong danh sách vừa hiển thị? Nếu chưa có kết quả, hãy tìm tour trước nhé.",List.of());
            case NO_MORE_RESULTS -> new Clarification(hint.name(),List.of(),"Danh sách hiện tại không còn trang tiếp theo. Bạn muốn thay đổi điều kiện nào để tìm thêm tour?",List.of());
            case UNSUPPORTED_FILTER -> new Clarification(hint.name(),List.of(),"Điều kiện đó chưa được hỗ trợ trong bộ lọc tư vấn hiện tại. Bạn có muốn tìm theo điểm đến, ngân sách, ngày đi và số người không?",List.of());
            default -> new Clarification(Hint.AMBIGUOUS_REQUEST.name(),List.of(),"Bạn nói rõ hơn điều kiện muốn thay đổi nhé. Tôi sẽ giữ các nhu cầu đã xác định trước đó.",List.of());
        };
    }
}
