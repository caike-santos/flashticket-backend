# Guia Prático: Strategy Pattern + Injeção de Lista no Spring Boot (SOLID)

Este documento explica como eliminar cadeias de `if/else` ou `switch` em regras de negócio usando **Polimorfismo**, **Injeção de Dependência do Spring (`@Component` + `List<Interface>`)** e o método **`supports()`**, aplicando os princípios **OCP (Open/Closed)** e **DIP (Dependency Inversion)** do SOLID.

---

## 1. Quando usar?
Use sempre que você tiver **uma mesma operação** cujo algoritmo muda conforme um **tipo, categoria, status ou canal**:
- **Meios de Pagamento**: `PIX`, `CREDIT_CARD`, `BOLETO`
- **Canais de Notificação**: `EMAIL`, `SMS`, `WHATSAPP`
- **Cálculo de Frete**: `SEDEX`, `PAC`, `TRANSPORTADORA`
- **Cálculo de Taxa por Categoria de Evento** *(implementado neste projeto)*: `CONCERT` (15%), `CONFERENCE` (10%), `WORKSHOP` (5%)

---

## 2. Como funciona nos bastidores do Spring Boot?

1. **Criação dos Objetos (`@Component`)**: Quando a aplicação inicia, o Spring escaneia todas as classes marcadas com `@Component` que implementam a sua interface e dá um `new` em cada uma delas (guardando esses objetos como *Beans* na memória).
2. **Injeção Polimórfica (`List<Interface>`)**: Quando uma classe `@Service` pede `List<SuaInterface>` no construtor, o Spring junta todos os objetos que implementam essa interface dentro da lista e injeta automaticamente.
3. **Seleção Dinâmica (`supports`)**: O método `supports(...)` em cada classe responde `true` ou `false` dizendo se aquele objeto atende o tipo solicitado na requisição.

---

## 3. Template Passo a Passo (Para Reutilizar em Outros Projetos)

### Passo 1: O Enum
```java
public enum PaymentType {
    PIX,
    CREDIT_CARD,
    BOLETO
}
```

### Passo 2: A Interface Strategy
```java
import java.math.BigDecimal;

public interface PaymentStrategy {
    boolean supports(PaymentType type);
    String pay(BigDecimal amount);
}
```

### Passo 3: As Classes Concretas (`@Component`)
```java
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class PixPaymentStrategy implements PaymentStrategy {

    @Override
    public boolean supports(PaymentType type) {
        return PaymentType.PIX == type;
    }

    @Override
    public String pay(BigDecimal amount) {
        return "Pagamento de R$ " + amount + " aprovado via PIX!";
    }
}
```

```java
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class CreditCardPaymentStrategy implements PaymentStrategy {

    @Override
    public boolean supports(PaymentType type) {
        return PaymentType.CREDIT_CARD == type;
    }

    @Override
    public String pay(BigDecimal amount) {
        return "Pagamento de R$ " + amount + " aprovado no Cartao de Credito!";
    }
}
```

### Passo 4: O Service que recebe a `List<PaymentStrategy>`
```java
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PaymentProcessorService {

    private final List<PaymentStrategy> strategies;

    public PaymentProcessorService(List<PaymentStrategy> strategies) {
        this.strategies = strategies;
    }

    public String executePayment(PaymentType type, BigDecimal amount) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(type))
                .findFirst()
                .map(strategy -> strategy.pay(amount))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nenhuma estrategia encontrada para o tipo: " + type));
    }
}
```

---

## 4. Otimização Avançada: Usando `Map<Enum, Strategy>` para Busca `O(1)`
Se o número de estratégias crescer muito e a chave for sempre um `Enum`, você pode indexar a lista em um `EnumMap` no construtor do Service:

```java
@Service
public class FastPaymentProcessorService {

    private final Map<PaymentType, PaymentStrategy> strategyMap;

    public FastPaymentProcessorService(List<PaymentStrategy> strategies) {
        this.strategyMap = new EnumMap<>(PaymentType.class);
        for (PaymentType type : PaymentType.values()) {
            strategies.stream()
                    .filter(s -> s.supports(type))
                    .findFirst()
                    .ifPresent(s -> strategyMap.put(type, s));
        }
    }

    public String executePayment(PaymentType type, BigDecimal amount) {
        PaymentStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Tipo nao suportado: " + type);
        }
        return strategy.pay(amount);
    }
}
```
