package br.com.kawashi.banco.testes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integracao de verdade: sobe a aplicacao inteira, conecta no Oracle real (como a conta de
 * API, por proxy) e chama a API por MockMvc. Cada teste roda dentro de uma transacao que e DESFEITA
 * no fim, entao nao sobra dado nenhum no banco e os testes nao interferem entre si.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public @interface IntegracaoApi {
}
