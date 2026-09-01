package com.leap.leaplaughlove.service;

import com.leap.leaplaughlove.entity.Client;
import com.leap.leaplaughlove.repository.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private ClientService clientService;

    private UUID clientId;
    private Client testClient;

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        testClient = new Client();
        testClient.setClientId(clientId);
        testClient.setEmail("client@test.com");
        testClient.setPhone("555-1234");
        testClient.setStatus("ACTIVE");
        testClient.setCreatedAt(OffsetDateTime.now());
    }

    @Test
    void testCreateClient_Success() {
        // Arrange
        when(clientRepository.save(any(Client.class))).thenReturn(testClient);

        // Act
        Client created = clientService.createClient(testClient);

        // Assert
        assertNotNull(created);
        assertEquals("client@test.com", created.getEmail());
        assertEquals("ACTIVE", created.getStatus());
        verify(clientRepository, times(1)).save(any(Client.class));
    }

    @Test
    void testGetClientById_Success() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));

        // Act
        Optional<Client> result = clientService.getClientById(clientId);

        // Assert
        assertTrue(result.isPresent());
        assertEquals(clientId, result.get().getClientId());
        assertEquals("client@test.com", result.get().getEmail());
    }

    @Test
    void testGetClientById_NotFound() {
        // Arrange
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        // Act
        Optional<Client> result = clientService.getClientById(clientId);

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testGetClientByEmail_Success() {
        // Arrange
        when(clientRepository.findByEmail("client@test.com")).thenReturn(Optional.of(testClient));

        // Act
        Optional<Client> result = clientService.getClientByEmail("client@test.com");

        // Assert
        assertTrue(result.isPresent());
        assertEquals("client@test.com", result.get().getEmail());
    }

    @Test
    void testGetClientByEmail_NotFound() {
        // Arrange
        when(clientRepository.findByEmail("nonexistent@test.com")).thenReturn(Optional.empty());

        // Act
        Optional<Client> result = clientService.getClientByEmail("nonexistent@test.com");

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testGetAllClients_Success() {
        // Arrange
        Client client2 = new Client();
        client2.setClientId(UUID.randomUUID());
        client2.setEmail("client2@test.com");
        client2.setStatus("ACTIVE");

        List<Client> clients = Arrays.asList(testClient, client2);
        when(clientRepository.findAll()).thenReturn(clients);

        // Act
        List<Client> result = clientService.getAllClients();

        // Assert
        assertEquals(2, result.size());
        verify(clientRepository, times(1)).findAll();
    }

    @Test
    void testGetAllClients_EmptyList() {
        // Arrange
        when(clientRepository.findAll()).thenReturn(Arrays.asList());

        // Act
        List<Client> result = clientService.getAllClients();

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void testUpdateClient_Success() {
        // Arrange
        Client updatedClient = new Client();
        updatedClient.setEmail("newemail@test.com");
        updatedClient.setPhone("555-9999");
        updatedClient.setStatus("LOCKED");

        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));
        when(clientRepository.save(any(Client.class))).thenReturn(testClient);

        // Act
        Client result = clientService.updateClient(clientId, updatedClient);

        // Assert
        assertNotNull(result);
        verify(clientRepository, times(1)).findById(clientId);
        verify(clientRepository, times(1)).save(any(Client.class));
    }

    @Test
    void testUpdateClient_NotFound_ThrowsException() {
        // Arrange
        Client updatedClient = new Client();
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            clientService.updateClient(clientId, updatedClient);
        });
        assertEquals("Client not found with id: " + clientId, exception.getMessage());
        verify(clientRepository, never()).save(any(Client.class));
    }

    @Test
    void testDeleteClient_Success() {
        // Arrange
        doNothing().when(clientRepository).deleteById(clientId);

        // Act
        clientService.deleteClient(clientId);

        // Assert
        verify(clientRepository, times(1)).deleteById(clientId);
    }

    @Test
    void testUpdateClient_OnlyUpdatesProvidedFields() {
        // Arrange
        Client updatedClient = new Client();
        updatedClient.setEmail("newemail@test.com");
        updatedClient.setPhone(null);  // Not updating phone
        updatedClient.setStatus(null); // Not updating status

        testClient.setEmail("original@test.com");
        testClient.setPhone("555-1234");
        testClient.setStatus("ACTIVE");

        when(clientRepository.findById(clientId)).thenReturn(Optional.of(testClient));
        when(clientRepository.save(any(Client.class))).thenReturn(testClient);

        // Act
        Client result = clientService.updateClient(clientId, updatedClient);

        // Assert
        assertNotNull(result);
        verify(clientRepository, times(1)).save(any(Client.class));
    }
}
